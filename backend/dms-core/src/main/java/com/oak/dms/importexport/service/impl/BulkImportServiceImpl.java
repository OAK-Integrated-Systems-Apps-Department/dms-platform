package com.oak.dms.importexport.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.importexport.config.ImportExportProperties;
import com.oak.dms.importexport.dto.BulkImportCreatedResponse;
import com.oak.dms.importexport.dto.BulkImportStatusResponse;
import com.oak.dms.importexport.dto.ImportDocumentTypeOption;
import com.oak.dms.importexport.dto.ImportFileReceivedResponse;
import com.oak.dms.importexport.dto.ImportRowFailure;
import com.oak.dms.importexport.dto.ImportRowProblem;
import com.oak.dms.importexport.dto.ImportTemplateFile;
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
import com.oak.dms.importexport.service.BulkImportAuditor;
import com.oak.dms.importexport.service.BulkImportService;
import com.oak.dms.importexport.service.ImportTemplateService;
import com.oak.dms.folder.FolderService;
import com.oak.dms.importexport.storage.IntakeVirusScanner;
import com.oak.dms.importexport.entity.ImportTemplate;
import com.oak.dms.importexport.template.ImportTemplateWorkbook.TemplateLayout;
import com.oak.dms.importexport.storage.StagedImportFiles;
import com.oak.dms.importexport.template.ImportTemplateWorkbook;
import com.oak.dms.importexport.template.ImportTemplateWorkbook.ParsedRow;
import com.oak.dms.importexport.template.ImportTemplateWorkbook.ParsedTemplate;
import com.oak.dms.importexport.validation.ImportField;
import com.oak.dms.importexport.validation.ImportRowError;
import com.oak.dms.importexport.validation.ImportRowValidator;
import com.oak.dms.importexport.validation.ImportRowValidator.RowValues;
import com.oak.dms.importexport.enums.TemplateFormat;
import com.oak.dms.importexport.template.ImportTemplateCsv;
import com.oak.dms.importexport.template.ImportTemplateWorkbook.PrefilledRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.ArrayList;
import java.util.Locale;
import java.util.stream.Collectors;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

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
    private static final Map<String, String> OWN_FIELD_LABELS = Map.of(
            "file", "File",
            ImportTemplateWorkbook.FILENAME_KEY, "Filename",
            ImportTemplateWorkbook.TITLE_KEY, "Title");
    /** ImportRowValidator's code for a row whose file hasn't arrived. */
    private static final String FILE_MISSING = "FILE_MISSING";
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
    private final BulkImportAuditor auditor;
    private final ImportTemplateCsv templateCsv;
    private final ImportRowValidator rowValidator;
    private final ImportTemplateService importTemplateService;
    private final FolderService folderService;
    private final IntakeVirusScanner intakeVirusScanner;

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
    public ImportTemplateFile generateTemplate(UUID documentTypeId, TemplateFormat format) {
        UUID tenantId = TenantContext.getTenantId();
        DocumentType documentType = documentTypeRepository
                .findByDocumentTypeIdAndTenantIdAndDeletedFalse(documentTypeId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("DocumentType", documentTypeId));
        requireActive(documentType);

        DocumentTypeSchemaResponse schema = documentTypeFieldService.getSchema(documentTypeId);
        String filename = safeFilenamePart(documentType.getTypeCode()) + "-import-template-v" + schema.schemaVersion();
        return render(format, filename, schema, List.of());
    }

    @Override
    public BulkImportCreatedResponse createBatch(String templateFilename, long templateSize, InputStream template,
                                                 UUID folderId) {
        if (folderId != null) {
            folderService.requireEditable(folderId); // 404/403 now - the worker doesn't check access again
        }
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
        ParsedTemplate parsed = isCsv(templateFilename)
                ? templateCsv.parse(template, maxRows)
                : templateWorkbook.parse(template, maxRows);

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

        // A file made from a saved template gets that template's defaults
        // for fields left out or blank - from here on they're ordinary cells.
        Map<String, String> defaults = parsed.importTemplateId() == null ? Map.of()
                : importTemplateService.defaultsForUpload(parsed.importTemplateId(), documentType.getDocumentTypeId());
        List<ParsedRow> rows = parsed.rows().stream().map(row -> withDefaults(row, defaults)).toList();
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
                .importTemplateId(parsed.importTemplateId())
                .folderId(folderId)
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
    @Transactional(readOnly = true)
    public List<ImportRowFailure> checkRows(UUID batchId) {
        UUID tenantId = TenantContext.getTenantId();
        ImportBatch batch = importBatchRepository
                .findByImportBatchIdAndTenantIdAndCreatedByAndDeletedFalse(batchId, tenantId, TenantContext.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("ImportBatch", batchId));
        List<ImportItem> items = importItemRepository.findAllByImportBatchIdOrderByRowNumberAsc(batchId);
        Map<String, ImportField> fields = importSchemaFields.load(tenantId, batch.getDocumentTypeId(),
                batch.getSchemaVersion());
        Map<String, List<Integer>> duplicates = ImportRowValidator.duplicateRows(items);

        List<ImportRowFailure> problems = new ArrayList<>();
        for (ImportItem item : items) {
            RowValues values = readRowValues(item);
            if (values == null) {
                continue;
            }
            List<ImportRowProblem> rowProblems = rowValidator.validate(item, values, fields, duplicates,
                            batch.getDocumentTypeId(), batch.getSchemaVersion(), tenantId).stream()
                    // Files are still arriving - the missing-files list covers that.
                    .filter(error -> !FILE_MISSING.equals(error.code()))
                    .map(error -> new ImportRowProblem(error.field(), fieldLabel(error.field(), fields), error.code(),
                            error.message(), error.severity()))
                    .toList();
            if (!rowProblems.isEmpty()) {
                problems.add(new ImportRowFailure(item.getRowNumber(), item.getFilenameReference(), values.title(),
                        rowProblems));
            }
        }
        return problems;
    }

    @Override
    @Transactional(readOnly = true)
    public ImportTemplateFile failedRowsTemplate(UUID batchId, TemplateFormat format) {
        UUID tenantId = TenantContext.getTenantId();
        ImportBatch batch = importBatchRepository
                .findByImportBatchIdAndTenantIdAndCreatedByAndDeletedFalse(batchId, tenantId, TenantContext.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("ImportBatch", batchId));
        List<ImportItem> failed = importItemRepository.findAllByImportBatchIdAndStatusOrderByRowNumberAsc(batchId,
                ImportItem.ImportItemStatus.failed);
        if (failed.isEmpty()) {
            throw new ResourceNotFoundException("Failed rows of ImportBatch", batchId);
        }
        DocumentType documentType = documentTypeRepository
                .findByDocumentTypeIdAndTenantIdAndDeletedFalse(batch.getDocumentTypeId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("DocumentType", batch.getDocumentTypeId()));
        requireActive(documentType);

        // The current schema, not the batch's: the report is a template to
        // upload again, and only a current-version template is accepted.
        // Cells of fields that no longer exist are dropped.
        DocumentTypeSchemaResponse schema = documentTypeFieldService.getSchema(batch.getDocumentTypeId());
        Map<String, ImportField> batchFields = importSchemaFields.load(tenantId, batch.getDocumentTypeId(),
                batch.getSchemaVersion());
        List<PrefilledRow> rows = failed.stream()
                .map(item -> {
                    RowValues values = readRowValues(item);
                    String problems = rowErrors(item).stream()
                            .filter(error -> ImportRowError.ERROR.equals(error.severity()))
                            .map(error -> {
                                String label = fieldLabel(error.field(), batchFields);
                                return label != null ? label + ": " + error.message() : error.message();
                            })
                            .collect(Collectors.joining("; "));
                    return new PrefilledRow(item.getFilenameReference(), values != null ? values.title() : null,
                            values != null && values.fields() != null ? values.fields() : Map.of(), problems);
                })
                .toList();
        String filename = safeFilenamePart(documentType.getTypeCode()) + "-failed-rows-"
                + batchId.toString().substring(0, 8);
        return render(format, filename, schema, rows);
    }

    @Override
    @Transactional(readOnly = true)
    public ImportTemplateFile generateFromSavedTemplate(UUID importTemplateId, TemplateFormat format) {
        ImportTemplate saved = importTemplateService.requireForDownload(importTemplateId);
        DocumentType documentType = documentTypeRepository
                .findByDocumentTypeIdAndTenantIdAndDeletedFalse(saved.getDocumentTypeId(), TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("DocumentType", saved.getDocumentTypeId()));
        DocumentTypeSchemaResponse schema = documentTypeFieldService.getSchema(saved.getDocumentTypeId());
        String filename = safeFilenamePart(documentType.getTypeCode()) + "-" + safeFilenamePart(saved.getName())
                + "-v" + schema.schemaVersion();
        return render(format, filename, schema, importTemplateService.layout(saved, schema), List.of());
    }

    /** Fills every default the row leaves out or blank; cells the user filled always win. */
    static ParsedRow withDefaults(ParsedRow row, Map<String, String> defaults) {
        if (defaults.isEmpty()) {
            return row;
        }
        Map<String, String> fields = new LinkedHashMap<>(row.fields());
        defaults.forEach((key, value) -> {
            String cell = fields.get(key);
            if (cell == null || cell.isBlank()) {
                fields.put(key, value);
            }
        });
        return new ParsedRow(row.rowNumber(), row.filename(), row.title(), fields);
    }

    private ImportTemplateFile render(TemplateFormat format, String baseName, DocumentTypeSchemaResponse schema,
                                      List<PrefilledRow> rows) {
        return render(format, baseName, schema, ImportTemplateWorkbook.standard(schema), rows);
    }

    private ImportTemplateFile render(TemplateFormat format, String baseName, DocumentTypeSchemaResponse schema,
                                      TemplateLayout layout, List<PrefilledRow> rows) {
        if (format == TemplateFormat.csv) {
            return new ImportTemplateFile(baseName + ".csv", ImportTemplateFile.CSV_CONTENT_TYPE,
                    templateCsv.generate(schema, layout, rows));
        }
        return new ImportTemplateFile(baseName + XLSX_EXTENSION,
                templateWorkbook.generate(schema, layout, properties.getMaxFilesPerBatch(), rows));
    }

    private static boolean isCsv(String filename) {
        return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".csv");
    }

    private RowValues readRowValues(ImportItem item) {
        try {
            return objectMapper.readValue(item.getRowValues(), RowValues.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.warn("Unreadable row_values on import item {}", item.getImportItemId(), e);
            return null;
        }
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

        // Only the rows naming this file, and the size so far as one aggregate -
        // not the whole batch per upload (a 500-file import would otherwise
        // read 500 rows 500 times).
        List<ImportItem> candidates = importItemRepository.findAllByBatchAndFilename(batchId,
                ImportFilenameMatcher.normalize(originalFilename));
        ImportItem item = filenameMatcher.match(candidates, originalFilename);

        long stagedBytes = importItemRepository.sumStagedBytes(batchId);
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

        // Virus scan now, before the file counts as received: an infected
        // file is refused while the user is still uploading (the rollback
        // deletes the staged object). Scanned again when its document is made.
        intakeVirusScanner.scan(tenantId, batchId, item.getImportItemId(), originalFilename, location, objectKey,
                stored.sizeBytes(), detected.mimeType());

        item.setStagedObjectKey(objectKey);
        item.setStagedStorageLocationId(location.getStorageLocationId());
        item.setStagedSizeBytes(stored.sizeBytes());
        item.setUpdatedAt(OffsetDateTime.now());
        importItemRepository.save(item);

        int filesReceived = (int) importItemRepository.countReceived(batchId);
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
        auditor.submitted(batch, (int) importItemRepository.countReceived(batchId));

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
        return statusOf(batch);
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
                        rowErrors(item).stream()
                                .map(error -> new ImportRowProblem(error.field(),
                                        fieldLabel(error.field(), schemaFields), error.code(), error.message(),
                                        error.severity()))
                                .toList()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BulkImportStatusResponse> listMine(Pageable pageable) {
        return importBatchRepository
                .findByTenantIdAndCreatedByAndDeletedFalse(TenantContext.getTenantId(), TenantContext.getUserId(), pageable)
                .map(this::statusOf);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BulkImportStatusResponse> getActive() {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();

        return importBatchRepository
                .findFirstByTenantIdAndCreatedByAndStatusInAndDeletedFalseOrderByCreatedAtDesc(tenantId, userId,
                        ACTIVE_STATUSES)
                .map(this::statusOf);
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
        auditor.cancelled(batch);

        log.info("Cancelled bulk import batch {} for tenant {} ({} staged files to delete)",
                batchId, tenantId, toDelete);
        return toStatusResponse(batch, items);
    }

    /** The batch's status from two small queries rather than every row. */
    private BulkImportStatusResponse statusOf(ImportBatch batch) {
        List<String> missing = importItemRepository.findMissingFilenames(batch.getImportBatchId());
        return new BulkImportStatusResponse(batch.getImportBatchId(), batch.getDocumentTypeId(),
                batch.getStatus().name(), batch.getTotalRecords(), batch.getTotalRecords() - missing.size(),
                batch.getSuccessCount(), batch.getFailureCount(), missing, batch.getCreatedAt());
    }

    private static BulkImportStatusResponse toStatusResponse(ImportBatch batch, List<ImportItem> items) {
        // A file handed on to the scan gate has left staging but was received.
        List<String> missingFilenames = items.stream()
                .filter(item -> item.getStagedObjectKey() == null && item.getScanResultId() == null)
                .map(ImportItem::getFilenameReference)
                .toList();
        return new BulkImportStatusResponse(batch.getImportBatchId(), batch.getDocumentTypeId(),
                batch.getStatus().name(), batch.getTotalRecords(), items.size() - missingFilenames.size(),
                batch.getSuccessCount(), batch.getFailureCount(), missingFilenames, batch.getCreatedAt());
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
