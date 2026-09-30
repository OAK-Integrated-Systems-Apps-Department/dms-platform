package com.oak.dms.importexport.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.importexport.config.ImportExportProperties;
import com.oak.dms.importexport.dto.BulkImportCreatedResponse;
import com.oak.dms.importexport.dto.BulkImportStatusResponse;
import com.oak.dms.importexport.dto.ImportFileReceivedResponse;
import com.oak.dms.importexport.dto.ImportTemplateFile;
import com.oak.dms.importexport.entity.ImportBatch;
import com.oak.dms.importexport.entity.ImportBatch.ImportBatchStatus;
import com.oak.dms.importexport.entity.ImportItem;
import com.oak.dms.importexport.exception.ActiveImportBatchLimitException;
import com.oak.dms.importexport.exception.ImportBatchSizeException;
import com.oak.dms.importexport.exception.ImportBatchStateException;
import com.oak.dms.importexport.exception.InactiveDocumentTypeException;
import com.oak.dms.importexport.exception.InvalidImportTemplateException;
import com.oak.dms.importexport.matching.ImportFilenameMatcher;
import com.oak.dms.importexport.repository.ImportBatchRepository;
import com.oak.dms.importexport.repository.ImportItemRepository;
import com.oak.dms.importexport.service.BulkImportService;
import com.oak.dms.importexport.template.ImportTemplateWorkbook;
import com.oak.dms.importexport.template.ImportTemplateWorkbook.ParsedRow;
import com.oak.dms.importexport.template.ImportTemplateWorkbook.ParsedTemplate;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse;
import com.oak.dms.metadata.entity.DocumentType;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.metadata.service.DocumentTypeFieldService;
import com.oak.dms.outbox.OutboxEventPublisher;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.dto.StoredObjectMetadata;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.service.ObjectStorageService;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import com.oak.dms.storage.service.StorageLocationResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * See {@link BulkImportService}. Staged files go through
 * {@link ObjectStorageServiceFactory}/{@link StorageLocationResolver} only,
 * under a server-generated key ({@code imports/{tenantId}/{batchId}/{itemId}}) -
 * never the uploaded filename.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class BulkImportServiceImpl implements BulkImportService {

    static final String AGGREGATE_TYPE = "import_batch";
    static final String SUBMITTED_EVENT_TYPE = "import_batch.submitted";

    private static final String XLSX_EXTENSION = ".xlsx";
    private static final List<ImportBatchStatus> ACTIVE_STATUSES =
            List.of(ImportBatchStatus.uploading, ImportBatchStatus.queued, ImportBatchStatus.running);

    private final ImportBatchRepository importBatchRepository;
    private final ImportItemRepository importItemRepository;
    private final DocumentTypeRepository documentTypeRepository;
    private final DocumentTypeFieldService documentTypeFieldService;
    private final ImportTemplateWorkbook templateWorkbook;
    private final ImportFilenameMatcher filenameMatcher;
    private final StorageLocationResolver storageLocationResolver;
    private final ObjectStorageServiceFactory objectStorageServiceFactory;
    private final OutboxEventPublisher outboxEventPublisher;
    private final ImportExportProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public ImportTemplateFile generateTemplate(UUID documentTypeId) {
        UUID tenantId = TenantContext.getTenantId();
        DocumentType documentType = documentTypeRepository
                .findByDocumentTypeIdAndTenantIdAndDeletedFalse(documentTypeId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("DocumentType", documentTypeId));
        requireActive(documentType);

        DocumentTypeSchemaResponse schema = documentTypeFieldService.getSchema(documentTypeId);
        byte[] content = templateWorkbook.generate(schema, properties.getMaxFilesPerBatch());
        String filename = safeFilenamePart(documentType.getTypeCode()) + "-import-template-v"
                + schema.schemaVersion() + XLSX_EXTENSION;
        return new ImportTemplateFile(filename, content);
    }

    @Override
    public BulkImportCreatedResponse createBatch(long templateSize, InputStream template) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();

        // POI holds the whole workbook in memory, so an oversized upload is
        // rejected before it's opened at all.
        long maxTemplateBytes = properties.getMaxTemplateSize().toBytes();
        if (templateSize > maxTemplateBytes) {
            throw new ImportBatchSizeException("IMPORT_TEMPLATE_TOO_LARGE",
                    "The template is larger than " + properties.getMaxTemplateSize().toMegabytes()
                            + "MB; split it into smaller imports.");
        }

        int maxRows = properties.getMaxFilesPerBatch();
        ParsedTemplate parsed = templateWorkbook.parse(template, maxRows);

        DocumentType documentType = documentTypeRepository
                .findByDocumentTypeIdAndTenantIdAndDeletedFalse(parsed.documentTypeId(), tenantId)
                .orElseThrow(ImportTemplateWorkbook::invalid);
        requireActive(documentType);
        if (parsed.schemaVersion() != documentType.getCurrentSchemaVersion()) {
            throw new InvalidImportTemplateException("IMPORT_TEMPLATE_OUTDATED",
                    "This template is for an older version of the '" + documentType.getTypeName()
                            + "' fields. Download a fresh template and try again.");
        }

        long activeBatches = importBatchRepository
                .countByTenantIdAndCreatedByAndStatusInAndDeletedFalse(tenantId, userId, ACTIVE_STATUSES);
        if (activeBatches >= properties.getMaxActiveBatchesPerUser()) {
            throw new ActiveImportBatchLimitException(
                    "You already have a bulk import in progress. Wait for it to finish before starting another.");
        }

        List<ParsedRow> rows = parsed.rows();
        if (rows.isEmpty()) {
            throw new ImportBatchSizeException("IMPORT_ROW_COUNT_INVALID", "The template has no data rows.");
        }

        ImportBatch batch = importBatchRepository.save(ImportBatch.builder()
                .tenantId(tenantId)
                .sourceType(ImportBatch.SourceType.bulk_import)
                .documentTypeId(documentType.getDocumentTypeId())
                .schemaVersion(parsed.schemaVersion())
                .status(ImportBatchStatus.uploading)
                .totalRecords(rows.size())
                .correlationId(UUID.randomUUID())
                .createdBy(userId)
                .build());

        List<ImportItem> items = rows.stream()
                .map(row -> ImportItem.builder()
                        .tenantId(tenantId)
                        .importBatchId(batch.getImportBatchId())
                        .rowNumber(row.rowNumber())
                        .filenameReference(row.filename())
                        .rowValues(toRowValuesJson(row))
                        .status(ImportItem.ImportItemStatus.pending)
                        .build())
                .toList();
        importItemRepository.saveAll(items);

        log.info("Created bulk import batch {} ({} rows) for document type {} v{} in tenant {}",
                batch.getImportBatchId(), rows.size(), documentType.getDocumentTypeId(), parsed.schemaVersion(),
                tenantId);

        return new BulkImportCreatedResponse(batch.getImportBatchId(), rows.size(),
                rows.stream().map(ParsedRow::filename).toList());
    }

    @Override
    public ImportFileReceivedResponse receiveFile(UUID batchId, String originalFilename, String contentType,
                                                  long contentLength, InputStream content) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();

        // Held until commit: serializes uploads into the same batch so two
        // requests can't claim the same row or both slip under the size cap.
        ImportBatch batch = importBatchRepository.lockByImportBatchIdAndTenantIdAndCreatedBy(batchId, tenantId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("ImportBatch", batchId));
        requireUploading(batch);

        List<ImportItem> items = importItemRepository.findAllByImportBatchIdOrderByRowNumberAsc(batchId);
        ImportItem item = filenameMatcher.match(items, originalFilename);

        long stagedBytes = items.stream()
                .map(ImportItem::getStagedSizeBytes)
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .sum();
        if (stagedBytes + contentLength > properties.getMaxBatchSize().toBytes()) {
            throw new ImportBatchSizeException("IMPORT_BATCH_SIZE_EXCEEDED",
                    "Adding this file would take the import over its " + properties.getMaxBatchSize().toMegabytes()
                            + "MB limit.");
        }

        StorageLocation location = storageLocationResolver.resolveForTenant(tenantId);
        String objectKey = buildStagedObjectKey(tenantId, batchId, item.getImportItemId());
        ObjectStorageService storage = objectStorageServiceFactory.resolve(location);

        // Upload before touching the row - a storage failure leaves the
        // item exactly as it was.
        StoredObjectMetadata stored = storage.upload(location, objectKey, content, contentLength, contentType);
        deleteOnRollback(storage, location, objectKey);

        item.setStagedObjectKey(objectKey);
        item.setStagedStorageLocationId(location.getStorageLocationId());
        item.setStagedSizeBytes(stored.sizeBytes());
        item.setUpdatedAt(OffsetDateTime.now());
        importItemRepository.save(item);

        int filesReceived = (int) items.stream().filter(i -> i.getStagedObjectKey() != null).count();
        log.info("Staged file for row {} of bulk import batch {} ({}/{} files)",
                item.getRowNumber(), batchId, filesReceived, batch.getTotalRecords());
        return new ImportFileReceivedResponse(item.getRowNumber(), filesReceived, batch.getTotalRecords());
    }

    @Override
    public void submit(UUID batchId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();

        ImportBatch batch = importBatchRepository.lockByImportBatchIdAndTenantIdAndCreatedBy(batchId, tenantId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("ImportBatch", batchId));
        requireUploading(batch);

        // Rows still missing a file are allowed through; the worker fails
        // them individually.
        batch.setStatus(ImportBatchStatus.queued);
        batch.setUpdatedBy(userId);
        batch.setUpdatedAt(OffsetDateTime.now());
        importBatchRepository.save(batch);

        outboxEventPublisher.publish(tenantId, AGGREGATE_TYPE, batchId, SUBMITTED_EVENT_TYPE,
                Map.of("importBatchId", batchId, "correlationId", batch.getCorrelationId()));

        log.info("Submitted bulk import batch {} for tenant {}", batchId, tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public BulkImportStatusResponse getStatus(UUID batchId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();

        ImportBatch batch = importBatchRepository
                .findByImportBatchIdAndTenantIdAndCreatedByAndDeletedFalse(batchId, tenantId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("ImportBatch", batchId));
        List<ImportItem> items = importItemRepository.findAllByImportBatchIdOrderByRowNumberAsc(batchId);

        List<String> missingFilenames = items.stream()
                .filter(item -> item.getStagedObjectKey() == null)
                .map(ImportItem::getFilenameReference)
                .toList();
        return new BulkImportStatusResponse(batch.getImportBatchId(), batch.getStatus().name(),
                batch.getTotalRecords(), items.size() - missingFilenames.size(),
                batch.getSuccessCount(), batch.getFailureCount(), missingFilenames);
    }

    private static void requireActive(DocumentType documentType) {
        if (documentType.getStatus() != DocumentType.DocumentTypeStatus.active) {
            throw new InactiveDocumentTypeException("Document type '" + documentType.getTypeName()
                    + "' is inactive and cannot be used for imports.");
        }
    }

    private static void requireUploading(ImportBatch batch) {
        if (batch.getStatus() != ImportBatchStatus.uploading) {
            throw new ImportBatchStateException("This import has already been submitted (status: "
                    + batch.getStatus().name() + ").");
        }
    }

    private String toRowValuesJson(ParsedRow row) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("title", row.title());
        values.put("fields", row.fields());
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("Failed to serialize row values for row " + row.rowNumber(), e);
        }
    }

    /**
     * If the transaction that records the staged object never commits (the
     * save fails, or the commit itself does), delete the object so it isn't
     * orphaned. Best-effort - a failed delete is logged, never rethrown.
     */
    private static void deleteOnRollback(ObjectStorageService storage, StorageLocation location, String objectKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    return;
                }
                try {
                    storage.delete(location, objectKey);
                } catch (RuntimeException e) {
                    log.warn("Failed to delete orphaned staged import object {}", objectKey, e);
                }
            }
        });
    }

    static String buildStagedObjectKey(UUID tenantId, UUID batchId, UUID itemId) {
        return "imports/" + tenantId + "/" + batchId + "/" + itemId;
    }

    private static String safeFilenamePart(String value) {
        return value == null || value.isBlank() ? "document-type" : value.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
