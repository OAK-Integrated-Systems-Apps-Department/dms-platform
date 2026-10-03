package com.oak.dms.importexport.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.importexport.config.ImportExportProperties;
import com.oak.dms.importexport.dto.BulkImportCreatedResponse;
import com.oak.dms.importexport.dto.BulkImportStatusResponse;
import com.oak.dms.importexport.dto.BulkImportSummary;
import com.oak.dms.importexport.dto.ImportDocumentTypeOption;
import com.oak.dms.importexport.dto.ImportFileReceivedResponse;
import com.oak.dms.importexport.dto.ImportRowFailure;
import com.oak.dms.importexport.dto.ImportRowProblem;
import com.oak.dms.importexport.dto.ImportTemplateFile;
import com.oak.dms.importexport.dto.ImportTemplatePreview;
import com.oak.dms.importexport.entity.ImportBatch;
import com.oak.dms.importexport.entity.ImportBatch.ImportBatchStatus;
import com.oak.dms.importexport.entity.ImportItem;
import com.oak.dms.importexport.exception.ActiveImportBatchLimitException;
import com.oak.dms.importexport.exception.ImportBatchNotCancellableException;
import com.oak.dms.importexport.exception.ImportBatchSizeException;
import com.oak.dms.importexport.exception.ImportBatchStateException;
import com.oak.dms.importexport.exception.InactiveDocumentTypeException;
import com.oak.dms.importexport.exception.InvalidImportTemplateException;
import com.oak.dms.importexport.matching.ImportFilenameMatcher;
import com.oak.dms.importexport.repository.ImportBatchRepository;
import com.oak.dms.importexport.repository.ImportItemRepository;
import com.oak.dms.importexport.service.BulkImportService;
import com.oak.dms.importexport.storage.StagedImportFiles;
import com.oak.dms.importexport.template.ImportTemplateWorkbook;
import com.oak.dms.importexport.template.ImportTemplateWorkbook.ParsedRow;
import com.oak.dms.importexport.template.ImportTemplateWorkbook.ParsedTemplate;
import com.oak.dms.importexport.validation.ImportField;
import com.oak.dms.importexport.validation.ImportRowError;
import com.oak.dms.importexport.validation.ImportRowValidator;
import com.oak.dms.importexport.validation.ImportRowValidator.RowValues;
import com.oak.dms.importexport.validation.ImportSchemaFields;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse;
import com.oak.dms.metadata.entity.DocumentType;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.metadata.service.DocumentTypeFieldService;
import com.oak.dms.outbox.OutboxEventPublisher;
import com.oak.dms.scanning.dto.DetectedFileType;
import com.oak.dms.scanning.exception.FileTypeNotAllowedException;
import com.oak.dms.scanning.helper.FileTypeInspector;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.dto.StoredObjectMetadata;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StoragePurpose;
import com.oak.dms.storage.service.ObjectStorageService;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import com.oak.dms.storage.service.StorageLocationResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * See {@link BulkImportService}. Staged files go through
 * {@link ObjectStorageServiceFactory}/{@link StorageLocationResolver} only,
 * under a server-generated key ({@code imports/{tenantId}/{batchId}/{itemId}}) -
 * never the uploaded filename. They are unscanned, so they wait in the
 * tenant's {@link StoragePurpose#staging} location, never the document
 * location - see docs/scanning-architecture.md. Each file's type is checked
 * by {@link FileTypeInspector} at intake, the same check the scan gate runs,
 * so a bad file is rejected before anything is stored.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class BulkImportServiceImpl implements BulkImportService {

    static final String AGGREGATE_TYPE = "import_batch";
    public static final String SUBMITTED_EVENT_TYPE = "import_batch.submitted";

    private static final String XLSX_EXTENSION = ".xlsx";
    private static final List<ImportBatchStatus> ACTIVE_STATUSES =
            List.of(ImportBatchStatus.uploading, ImportBatchStatus.queued, ImportBatchStatus.running);
    private static final List<ImportBatchStatus> CANCELLABLE_STATUSES =
            List.of(ImportBatchStatus.uploading, ImportBatchStatus.queued);
    static final int MAX_LIST_PAGE_SIZE = 50;
    private static final Sort NEWEST_FIRST =
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("importBatchId"));
    private static final Map<String, String> OWN_FIELD_LABELS = Map.of(
            "file", "File",
            ImportTemplateWorkbook.FILENAME_KEY, "Filename",
            ImportTemplateWorkbook.TITLE_KEY, "Title");
    private static final TypeReference<List<ImportRowError>> ROW_ERRORS = new TypeReference<>() {
    };

    private final ImportBatchRepository importBatchRepository;
    private final ImportItemRepository importItemRepository;
    private final DocumentTypeRepository documentTypeRepository;
    private final DocumentTypeFieldService documentTypeFieldService;
    private final ImportTemplateWorkbook templateWorkbook;
    private final ImportFilenameMatcher filenameMatcher;
    private final StorageLocationResolver storageLocationResolver;
    private final ObjectStorageServiceFactory objectStorageServiceFactory;
    private final StagedImportFiles stagedImportFiles;
    private final OutboxEventPublisher outboxEventPublisher;
    private final ImportExportProperties properties;
    private final ObjectMapper objectMapper;
    private final FileTypeInspector fileTypeInspector;
    private final ImportSchemaFields importSchemaFields;
    private final ImportRowValidator rowValidator;

    @Override
    @Transactional(readOnly = true)
    public List<ImportDocumentTypeOption> listDocumentTypes() {
        // Tenants have few document types, so the existing display-ordered
        // list is filtered in memory rather than adding a query to the
        // metadata repository. That query already drops deleted types; the
        // check here just keeps the rule explicit.
        return documentTypeRepository.findAllByTenantOrderedByDisplayOrder(TenantContext.getTenantId()).stream()
                .filter(type -> type.getStatus() == DocumentType.DocumentTypeStatus.active && !type.isDeleted())
                .map(type -> new ImportDocumentTypeOption(type.getDocumentTypeId(), type.getTypeName(),
                        type.getTypeCode()))
                .toList();
    }

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
    @Transactional(readOnly = true)
    public ImportTemplatePreview previewTemplate(long templateSize, InputStream template) {
        UUID tenantId = TenantContext.getTenantId();

        CheckedTemplate checked = readTemplate(templateSize, template, tenantId);
        RowCheckResult result = checkRows(checked, tenantId);

        // Read-only: nothing is saved, and the active-batch limit is left to
        // createBatch - checking a template doesn't start an import.
        List<ImportRowFailure> problemRows = result.rows().stream()
                .filter(CheckedRow::hasErrors)
                .map(row -> new ImportRowFailure(row.item().getRowNumber(), row.item().getFilenameReference(),
                        row.title(), toProblems(row.errors(), result.fields())))
                .toList();
        int totalRows = result.rows().size();
        return new ImportTemplatePreview(checked.documentType().getDocumentTypeId(), checked.parsed().schemaVersion(),
                totalRows, totalRows - problemRows.size(), problemRows.size(), problemRows);
    }

    @Override
    public BulkImportCreatedResponse createBatch(long templateSize, InputStream template) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();

        CheckedTemplate checked = readTemplate(templateSize, template, tenantId);
        DocumentType documentType = checked.documentType();
        ParsedTemplate parsed = checked.parsed();

        long activeBatches = importBatchRepository
                .countByTenantIdAndCreatedByAndStatusInAndDeletedFalse(tenantId, userId, ACTIVE_STATUSES);
        if (activeBatches >= properties.getMaxActiveBatchesPerUser()) {
            throw new ActiveImportBatchLimitException(
                    "You already have a bulk import in progress. Wait for it to finish before starting another.");
        }

        // Always re-checked here, whatever the preview said: rows with
        // errors are saved failed (and skipped from then on), the rest wait
        // for their files as before.
        List<CheckedRow> rows = checkRows(checked, tenantId).rows();
        if (rows.stream().allMatch(CheckedRow::hasErrors)) {
            throw new ImportBatchSizeException("IMPORT_NO_VALID_ROWS",
                    "None of the template's rows can be imported. Fix the problems and upload it again.");
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
                .map(row -> {
                    ImportItem item = row.item();
                    item.setImportBatchId(batch.getImportBatchId());
                    if (row.hasErrors()) {
                        item.setStatus(ImportItem.ImportItemStatus.failed);
                        item.setSkippedAtPreview(true);
                        item.setValidationErrors(toJson(row.errors()));
                    }
                    return item;
                })
                .toList();
        importItemRepository.saveAll(items);

        List<String> expectedFilenames = items.stream()
                .filter(item -> !item.isSkippedAtPreview())
                .map(ImportItem::getFilenameReference)
                .toList();
        int skippedRows = items.size() - expectedFilenames.size();

        log.info("Created bulk import batch {} ({} rows, {} skipped for template errors) for document type {} v{}"
                        + " in tenant {}", batch.getImportBatchId(), rows.size(), skippedRows,
                documentType.getDocumentTypeId(), parsed.schemaVersion(), tenantId);

        return new BulkImportCreatedResponse(batch.getImportBatchId(), rows.size(), expectedFilenames, skippedRows);
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

        // Type check on the head of the stream, as the scan gate does - a
        // rejected file stages nothing. The client's Content-Type is never
        // trusted; the inspector's canonical MIME type is stored instead.
        BufferedInputStream buffered = new BufferedInputStream(content, FileTypeInspector.HEAD_BYTES * 2);
        byte[] head = peek(buffered);
        DetectedFileType detected = fileTypeInspector.inspect(originalFilename, head, head.length);
        if (detected.encrypted()) {
            // The gate would mark it unscannable anyway - reject it now.
            throw new FileTypeNotAllowedException("Password-protected files can't be scanned for malware. "
                    + "Remove the password and upload the file again.");
        }

        StorageLocation location = storageLocationResolver.resolveForTenant(tenantId, StoragePurpose.staging);
        String objectKey = buildStagedObjectKey(tenantId, batchId, item.getImportItemId());
        ObjectStorageService storage = objectStorageServiceFactory.resolve(location);

        // Upload before touching the row - a storage failure leaves the
        // item exactly as it was.
        StoredObjectMetadata stored = storage.upload(location, objectKey, buffered, contentLength,
                detected.mimeType());
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

        // TODO(import worker): a queued batch can still be cancelled (see
        // cancel), so the worker must re-check status == queued under
        // lockByImportBatchIdAndTenantIdAndCreatedBy before it processes
        // this event, and skip the batch otherwise.
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
        return toStatusResponse(batch, importItemRepository.findAllByImportBatchIdOrderByRowNumberAsc(batchId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ImportRowFailure> getFailures(UUID batchId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();

        ImportBatch batch = importBatchRepository
                .findByImportBatchIdAndTenantIdAndCreatedByAndDeletedFalse(batchId, tenantId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("ImportBatch", batchId));
        List<ImportItem> failed = importItemRepository.findAllByImportBatchIdAndStatusOrderByRowNumberAsc(batchId,
                ImportItem.ImportItemStatus.failed);
        if (failed.isEmpty()) {
            return List.of();
        }

        // Labels come from the batch's own schema version, the one its
        // template (and so its error field keys) was built from.
        Map<String, ImportField> schemaFields = importSchemaFields.load(tenantId, batch.getDocumentTypeId(),
                batch.getSchemaVersion());
        return failed.stream()
                .map(item -> new ImportRowFailure(item.getRowNumber(), item.getFilenameReference(), rowTitle(item),
                        toProblems(rowErrors(item), schemaFields)))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BulkImportStatusResponse> getActive() {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();

        return importBatchRepository
                .findFirstByTenantIdAndCreatedByAndStatusInAndDeletedFalseOrderByCreatedAtDesc(tenantId, userId,
                        ACTIVE_STATUSES)
                .map(batch -> toStatusResponse(batch,
                        importItemRepository.findAllByImportBatchIdOrderByRowNumberAsc(batch.getImportBatchId())));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BulkImportSummary> listBatches(Pageable pageable) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();

        // Always newest first, whatever sort the client asked for, and the
        // size is capped so one request can't pull the whole history.
        PageRequest request = PageRequest.of(pageable.getPageNumber(),
                Math.min(pageable.getPageSize(), MAX_LIST_PAGE_SIZE), NEWEST_FIRST);
        Page<ImportBatch> batches = importBatchRepository.findAllByTenantIdAndCreatedByAndDeletedFalse(tenantId,
                userId, request);

        // One lookup for the whole page. Inactive or deleted types keep their
        // name here (this is history). The tenant filter keeps another
        // tenant's type from being named, even though a batch can't point at
        // one.
        Map<UUID, String> typeNames = new HashMap<>();
        Set<UUID> typeIds = batches.stream().map(ImportBatch::getDocumentTypeId).collect(Collectors.toSet());
        if (!typeIds.isEmpty()) {
            documentTypeRepository.findAllById(typeIds).stream()
                    .filter(type -> tenantId.equals(type.getTenantId()))
                    .forEach(type -> typeNames.put(type.getDocumentTypeId(), type.getTypeName()));
        }

        return batches.map(batch -> new BulkImportSummary(batch.getImportBatchId(), batch.getDocumentTypeId(),
                typeNames.get(batch.getDocumentTypeId()), batch.getStatus().name(), batch.getTotalRecords(),
                batch.getSuccessCount(), batch.getFailureCount(), batch.getCreatedAt(), batch.getCompletedAt()));
    }

    @Override
    public BulkImportStatusResponse cancel(UUID batchId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();

        // Same lock as receiveFile/submit, so a cancel can't interleave with
        // an in-flight upload into this batch.
        ImportBatch batch = importBatchRepository.lockByImportBatchIdAndTenantIdAndCreatedBy(batchId, tenantId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("ImportBatch", batchId));
        requireCancellable(batch);

        batch.setStatus(ImportBatchStatus.cancelled);
        batch.setUpdatedBy(userId);
        batch.setUpdatedAt(OffsetDateTime.now());
        importBatchRepository.save(batch);

        List<ImportItem> items = importItemRepository.findAllByImportBatchIdOrderByRowNumberAsc(batchId);
        int toDelete = stagedImportFiles.clearAndDeleteAfterCommit(items);
        importItemRepository.saveAll(items);

        log.info("Cancelled bulk import batch {} for tenant {} ({} staged files to delete)",
                batchId, tenantId, toDelete);
        return toStatusResponse(batch, items);
    }

    private static BulkImportStatusResponse toStatusResponse(ImportBatch batch, List<ImportItem> items) {
        // Rows skipped for template errors never need a file, so they are
        // neither missing nor received. A file handed on to the scan gate
        // has left staging but was received.
        int skippedRows = (int) items.stream().filter(ImportItem::isSkippedAtPreview).count();
        List<String> missingFilenames = items.stream()
                .filter(item -> !item.isSkippedAtPreview())
                .filter(item -> item.getStagedObjectKey() == null && item.getScanResultId() == null)
                .map(ImportItem::getFilenameReference)
                .toList();
        return new BulkImportStatusResponse(batch.getImportBatchId(), batch.getDocumentTypeId(),
                batch.getStatus().name(), batch.getTotalRecords(),
                items.size() - skippedRows - missingFilenames.size(), batch.getSuccessCount(),
                batch.getFailureCount(), missingFilenames, skippedRows, batch.getCreatedAt());
    }

    /**
     * The template-level checks preview and createBatch share, in this
     * order: size, parse (including the row limit), document type (in the
     * caller's tenant, active), schema version, at least one row. Each
     * failure is the same 400 from both endpoints.
     */
    private CheckedTemplate readTemplate(long templateSize, InputStream template, UUID tenantId) {
        // POI holds the whole workbook in memory, so an oversized upload is
        // rejected before it's opened at all.
        long maxTemplateBytes = properties.getMaxTemplateSize().toBytes();
        if (templateSize > maxTemplateBytes) {
            throw new ImportBatchSizeException("IMPORT_TEMPLATE_TOO_LARGE",
                    "The template is larger than " + properties.getMaxTemplateSize().toMegabytes()
                            + "MB; split it into smaller imports.");
        }

        ParsedTemplate parsed = templateWorkbook.parse(template, properties.getMaxFilesPerBatch());

        DocumentType documentType = documentTypeRepository
                .findByDocumentTypeIdAndTenantIdAndDeletedFalse(parsed.documentTypeId(), tenantId)
                .orElseThrow(ImportTemplateWorkbook::invalid);
        requireActive(documentType);
        if (parsed.schemaVersion() != documentType.getCurrentSchemaVersion()) {
            throw new InvalidImportTemplateException("IMPORT_TEMPLATE_OUTDATED",
                    "This template is for an older version of the '" + documentType.getTypeName()
                            + "' fields. Download a fresh template and try again.");
        }

        if (parsed.rows().isEmpty()) {
            throw new ImportBatchSizeException("IMPORT_ROW_COUNT_INVALID", "The template has no data rows.");
        }
        return new CheckedTemplate(documentType, parsed);
    }

    /**
     * Runs {@link ImportRowValidator#validateWithoutFile} on every row - all
     * the worker's checks except the file one - against the template's
     * schema version. The items are built but not saved.
     */
    private RowCheckResult checkRows(CheckedTemplate checked, UUID tenantId) {
        ParsedTemplate parsed = checked.parsed();
        UUID documentTypeId = checked.documentType().getDocumentTypeId();
        Map<String, ImportField> fields = importSchemaFields.load(tenantId, documentTypeId, parsed.schemaVersion());

        List<ImportItem> items = parsed.rows().stream()
                .map(row -> ImportItem.builder()
                        .tenantId(tenantId)
                        .rowNumber(row.rowNumber())
                        .filenameReference(row.filename())
                        .rowValues(toRowValuesJson(row))
                        .status(ImportItem.ImportItemStatus.pending)
                        .build())
                .toList();
        Map<String, List<Integer>> duplicates = ImportRowValidator.duplicateRows(items);

        List<CheckedRow> rows = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            ParsedRow row = parsed.rows().get(i);
            ImportItem item = items.get(i);
            List<ImportRowError> errors = rowValidator.validateWithoutFile(item,
                    new RowValues(row.title(), row.fields()), fields, duplicates, documentTypeId,
                    parsed.schemaVersion(), tenantId);
            rows.add(new CheckedRow(item, row.title(), errors));
        }
        return new RowCheckResult(fields, rows);
    }

    private record CheckedTemplate(DocumentType documentType, ParsedTemplate parsed) {
    }

    private record RowCheckResult(Map<String, ImportField> fields, List<CheckedRow> rows) {
    }

    private record CheckedRow(ImportItem item, String title, List<ImportRowError> errors) {
        boolean hasErrors() {
            return ImportRowValidator.hasErrors(errors);
        }
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

    private static void requireCancellable(ImportBatch batch) {
        if (!CANCELLABLE_STATUSES.contains(batch.getStatus())) {
            throw new ImportBatchNotCancellableException("This import can no longer be cancelled (status: "
                    + batch.getStatus().name() + ").");
        }
    }

    /** Display name of an error's column: 2.1's own columns, then the schema's label, then the raw key. */
    static String fieldLabel(String field, Map<String, ImportField> schemaFields) {
        if (field == null) {
            return null;
        }
        String own = OWN_FIELD_LABELS.get(field);
        if (own != null) {
            return own;
        }
        ImportField schemaField = schemaFields.get(field);
        return schemaField != null && schemaField.label() != null ? schemaField.label() : field;
    }

    /** A row's errors as the failures report and the preview show them, each with its column's label. */
    private static List<ImportRowProblem> toProblems(List<ImportRowError> errors,
                                                     Map<String, ImportField> schemaFields) {
        return errors.stream()
                .map(error -> new ImportRowProblem(error.field(), fieldLabel(error.field(), schemaFields),
                        error.code(), error.message(), error.severity()))
                .toList();
    }

    // The failures report is read-only and best-effort: one unreadable row
    // is logged and shown with what's left, rather than failing the report.

    private String rowTitle(ImportItem item) {
        try {
            return objectMapper.readValue(item.getRowValues(), RowValues.class).title();
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.warn("Unreadable row_values on import item {}", item.getImportItemId(), e);
            return null;
        }
    }

    private List<ImportRowError> rowErrors(ImportItem item) {
        if (item.getValidationErrors() == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(item.getValidationErrors(), ROW_ERRORS);
        } catch (JsonProcessingException e) {
            log.warn("Unreadable validation_errors on import item {}", item.getImportItemId(), e);
            return List.of();
        }
    }

    private String toJson(List<ImportRowError> errors) {
        try {
            return objectMapper.writeValueAsString(errors);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("Failed to serialize import row errors", e);
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

    /** Reads the head the inspector needs and rewinds, so the upload still sees every byte. */
    private static byte[] peek(BufferedInputStream in) {
        try {
            in.mark(FileTypeInspector.HEAD_BYTES);
            byte[] head = in.readNBytes(FileTypeInspector.HEAD_BYTES);
            in.reset();
            return head;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded file", e);
        }
    }

    static String buildStagedObjectKey(UUID tenantId, UUID batchId, UUID itemId) {
        return "imports/" + tenantId + "/" + batchId + "/" + itemId;
    }

    private static String safeFilenamePart(String value) {
        return value == null || value.isBlank() ? "document-type" : value.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
