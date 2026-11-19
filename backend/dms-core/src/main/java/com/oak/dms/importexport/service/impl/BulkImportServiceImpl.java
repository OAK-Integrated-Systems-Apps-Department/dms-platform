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
import com.oak.dms.importexport.exception.ImportFileAlreadyReceivedException;
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
import com.oak.dms.importexport.storage.ExactLengthInputStream;
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
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.BufferedInputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
// No class-level @Transactional (DOC-11): receiveFile must run with NO transactional advice at all - see its Javadoc.
// Every other method that writes declares its own.
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
    // The status response is read by a browser, not a paged screen of its
    // own - a batch of thousands of rows can skip thousands of files, and
    // the response must stay a bounded size regardless. skippedTotal in the
    // DTO carries the rest.
    private static final int OCR_SKIPPED_FILENAMES_LIMIT = 100;
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
    private final PlatformTransactionManager transactionManager;

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
    @Transactional
    public BulkImportCreatedResponse createBatch(String templateFilename, long templateSize, InputStream template,
                                                 UUID folderId, String duplicateAction, List<String> ocrFields) {
        String onDuplicate = duplicateAction == null || duplicateAction.isBlank() ? "keep_both" : duplicateAction;
        if (!java.util.Set.of("keep_both", "new_version", "skip").contains(onDuplicate)) {
            throw com.oak.dms.importexport.exception.BulkImportException.badRequest("IMPORT_DUPLICATE_ACTION_INVALID",
                    "Choose what to do with files already in the DMS: keep_both, new_version or skip.");
        }
        if (folderId != null) {
            folderService.requireEditable(folderId); // 404/403 now; the worker checks again for every row
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
                .duplicateAction(onDuplicate)
                .ocrRequestedFields(ocrFieldsJson(documentType, ocrFields))
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

    /** What the first transaction of an upload hands on: plain values - no lock, no managed entity. */
    private record UploadPlan(UUID itemId, int rowNumber, int totalRecords) {
    }

    /** What the last transaction found when the outcome of its commit had to be confirmed. */
    private record Confirmation(boolean recorded, boolean known, int filesReceived, int rowNumber, int totalRecords) {
    }

    /**
     * Stages one file of a bulk import in three steps (DOC-11). Storage I/O and the virus scan can take minutes and
     * must not sit inside a database transaction - that holds a pooled connection and the batch row lock for as long,
     * and a handful of slow uploads stopped the whole application. So:
     * <ol>
     *   <li>a <b>short transaction</b> takes the batch lock, checks the batch accepts uploads, finds the row the file
     *       belongs to and checks the declared size against the budget, then commits and lets go of the lock;</li>
     *   <li><b>no transaction at all</b>: type check, upload to a key made for this attempt alone, DOC-10 exact-length
     *       verification, virus scan;</li>
     *   <li>a <b>short transaction</b> takes the batch lock again and decides from the committed state as it is now:
     *       still uploading (not cancelled, not submitted), the row still unstaged, the verified size still fits the
     *       budget. Only then is the item recorded. A refusal here deletes this attempt's object.</li>
     * </ol>
     * Nothing is reserved between the steps - the invariants the old lock protected are simply checked again at the end,
     * so a race costs wasted work, never a broken rule.
     * <p>
     * This method carries <b>no transactional advice at all</b>, deliberately - not even {@code NEVER} or
     * {@code NOT_SUPPORTED}: those still create an "empty" transaction with synchronization, which binds an
     * {@code EntityManager} to the thread for the whole method, and the first repository call made during the I/O then
     * keeps a pooled connection until the method returns (found by the concurrency test, see
     * {@code BulkImportConcurrencyIT}). Called from inside someone else's transaction it fails at once instead
     * ({@link #requireNoTransaction}), rather than hold that transaction's connection across the I/O.
     */
    @Override
    public ImportFileReceivedResponse receiveFile(UUID batchId, String originalFilename, String contentType,
                                                  long contentLength, InputStream content) {
        requireNoTransaction();
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        if (contentLength < 0) {
            throw com.oak.dms.importexport.exception.BulkImportException.badRequest("IMPORT_FILE_SIZE_UNKNOWN",
                    "The size of '" + originalFilename + "' isn't known, so it can't be staged.");
        }

        // 1. Short transaction: is this file welcome, and for which row?
        UploadPlan plan = inTransaction(status -> planUpload(batchId, tenantId, userId, originalFilename,
                contentLength));

        // 2. No transaction: type check, upload under this attempt's own key, verify, scan.
        requireNoTransaction();
        // Type check on the head of the stream, as the scan gate does - a rejected file stages nothing. The client's
        // Content-Type is never trusted; the inspector's canonical MIME type is stored instead.
        BufferedInputStream buffered = new BufferedInputStream(content, FileTypeInspector.HEAD_BYTES * 2);
        byte[] head = peek(buffered);
        DetectedFileType detected = fileTypeInspector.inspect(originalFilename, head, head.length);
        if (detected.encrypted()) {
            // The gate would mark it unscannable anyway - reject it now.
            throw new FileTypeNotAllowedException("Password-protected files can't be scanned for malware. "
                    + "Remove the password and upload the file again.");
        }
        StorageLocation location = storageLocationResolver.resolveForTenant(tenantId, StoragePurpose.staging);
        // Unique to this attempt: two attempts for the same row can never overwrite or delete one another's object.
        String objectKey = buildStagedObjectKey(tenantId, batchId, plan.itemId()) + "/" + UUID.randomUUID();
        ObjectStorageService storage = objectStorageServiceFactory.resolve(location);
        StagedFile staged;
        try {
            staged = stageAndScan(tenantId, batchId, plan.itemId(), originalFilename, contentLength, buffered,
                    detected, location, objectKey, storage);
        } catch (RuntimeException e) {
            discardAttempt(storage, location, objectKey, e); // nothing was ever recorded for this key
            throw e;
        }

        // 3. Short transaction: decide again, now, under the lock, and record.
        AtomicBoolean checksPassed = new AtomicBoolean();
        try {
            ImportFileReceivedResponse response = inTransaction(status -> {
                ImportFileReceivedResponse recorded = finalizeUpload(batchId, tenantId, userId, originalFilename,
                        plan, location, objectKey, staged);
                checksPassed.set(true);
                return recorded;
            });
            log.info("Staged file for row {} of bulk import batch {} ({}/{} files)", response.rowNumber(), batchId,
                    response.filesReceived(), response.totalRecords());
            return response;
        } catch (RuntimeException e) {
            return resolveFinalization(e, checksPassed.get(), batchId, tenantId, userId, plan, location, objectKey,
                    storage);
        }
    }

    private UploadPlan planUpload(UUID batchId, UUID tenantId, UUID userId, String originalFilename,
                                  long contentLength) {
        // Held until this short transaction commits: the batch's state, the row and the budget are read as one.
        ImportBatch batch = importBatchRepository.lockByImportBatchIdAndTenantIdAndCreatedBy(batchId, tenantId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("ImportBatch", batchId));
        requireUploading(batch);

        // Only the rows naming this file, and the size so far as one aggregate - not the whole batch per upload.
        List<ImportItem> candidates = importItemRepository.findAllByBatchAndFilename(batchId,
                ImportFilenameMatcher.normalize(originalFilename));
        ImportItem item = filenameMatcher.match(candidates, originalFilename);

        // DOC-10: contentLength is only a claim, but a claim that cannot fit is refused before any I/O. Subtraction, not
        // addition: a forged ZIP64 size near Long.MAX_VALUE must not overflow the sum. This is preliminary - nothing is
        // reserved; the real (counted) size is checked again when the item is recorded.
        long remainingBytes = properties.getMaxBatchSize().toBytes() - importItemRepository.sumStagedBytes(batchId);
        if (contentLength > remainingBytes) {
            throw batchSizeExceeded();
        }
        return new UploadPlan(item.getImportItemId(), item.getRowNumber(), batch.getTotalRecords());
    }

    private record StagedFile(long sizeBytes, String sha256) {
    }

    /** Upload under this attempt's key, verify the bytes (DOC-10), scan. Runs with no transaction. */
    private StagedFile stageAndScan(UUID tenantId, UUID batchId, UUID itemId, String originalFilename,
                                    long contentLength, BufferedInputStream buffered, DetectedFileType detected,
                                    StorageLocation location, String objectKey, ObjectStorageService storage) {
        requireNoTransaction();
        // Fingerprinted as it streams in - the duplicate check uses it before the row becomes a document.
        java.security.MessageDigest sha256;
        try {
            sha256 = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        // The bytes actually read are counted, and exactly contentLength of them are handed to storage (DOC-10).
        ExactLengthInputStream counted = new ExactLengthInputStream(buffered, contentLength);
        try {
            StoredObjectMetadata stored = storage.upload(location, objectKey,
                    new java.security.DigestInputStream(counted, sha256), contentLength, detected.mimeType());
            // An adapter that stops at the declared length never asks for more, so look for excess (and for a short
            // or truncated object) here rather than trust the adapter's own report of the stored size.
            if (!counted.isExactlyConsumed() || stored.sizeBytes() != counted.count()) {
                throw sizeMismatch(originalFilename, contentLength);
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof com.oak.dms.importexport.exception.BulkImportException) {
                throw (com.oak.dms.importexport.exception.BulkImportException) e;
            }
            if (counted.sourceEndedEarly()) {
                throw sizeMismatch(originalFilename, contentLength); // storage failed because the stream was short
            }
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new UncheckedIOException("Could not read '" + originalFilename + "'", (IOException) e);
        }

        // Virus scan now, before the file counts as received: an infected file is refused while the user is still
        // uploading. Scanned again when its document is made.
        intakeVirusScanner.scan(tenantId, batchId, itemId, originalFilename, location, objectKey, counted.count(),
                detected.mimeType());
        return new StagedFile(counted.count(), java.util.HexFormat.of().formatHex(sha256.digest()));
    }

    /** The last short transaction: every rule the old long lock protected, checked against the state as it is now. */
    private ImportFileReceivedResponse finalizeUpload(UUID batchId, UUID tenantId, UUID userId, String originalFilename,
                                                      UploadPlan plan, StorageLocation location, String objectKey,
                                                      StagedFile staged) {
        ImportBatch batch = importBatchRepository.lockByImportBatchIdAndTenantIdAndCreatedBy(batchId, tenantId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("ImportBatch", batchId));
        requireUploading(batch); // cancelled or submitted while the file was being stored
        ImportItem item = importItemRepository.findById(plan.itemId())
                .filter(found -> batchId.equals(found.getImportBatchId()))
                .orElseThrow(() -> new ResourceNotFoundException("ImportItem", plan.itemId()));
        if (item.getStagedObjectKey() != null || item.getScanResultId() != null) {
            throw new ImportFileAlreadyReceivedException("'" + originalFilename + "' was uploaded for this row by "
                    + "another request while this one was being stored.");
        }
        // The cap, against what is committed now and the size that was really counted. Overflow-safe.
        long remainingBytes = properties.getMaxBatchSize().toBytes() - importItemRepository.sumStagedBytes(batchId);
        if (staged.sizeBytes() > remainingBytes) {
            throw batchSizeExceeded();
        }

        item.setStagedObjectKey(objectKey);
        item.setStagedStorageLocationId(location.getStorageLocationId());
        item.setStagedSizeBytes(staged.sizeBytes()); // verified: what was read, which equals what storage holds
        item.setIntakeBlockedReason(null); // a clean file replaced one that was refused
        item.setStagedFileHash(staged.sha256());
        item.setUpdatedAt(OffsetDateTime.now());
        importItemRepository.save(item);

        int filesReceived = (int) importItemRepository.countReceived(batchId);
        return new ImportFileReceivedResponse(item.getRowNumber(), filesReceived, batch.getTotalRecords());
    }

    /**
     * The last transaction ended in an exception. What that means depends on how far it got:
     * <ul>
     *   <li>our own checks or writes failed, so no commit was ever attempted: the object belongs to nobody - delete it;</li>
     *   <li>the checks passed and the <b>commit</b> failed (a lost connection, an unexpected rollback): the item may or
     *       may not be there. Ask the database - under the batch lock, so a commit still in flight finishes first - and
     *       look for this attempt's key. Recorded: the upload succeeded, keep the object. Not recorded: delete it. Could
     *       not be determined: keep the object, say so loudly, and tell the caller - an accepted file must never be
     *       deleted on a guess.</li>
     * </ul>
     */
    private ImportFileReceivedResponse resolveFinalization(RuntimeException failure, boolean checksPassed,
                                                           UUID batchId, UUID tenantId, UUID userId, UploadPlan plan,
                                                           StorageLocation location, String objectKey,
                                                           ObjectStorageService storage) {
        if (!checksPassed) {
            discardAttempt(storage, location, objectKey, failure);
            throw failure;
        }
        Confirmation confirmation = confirmCommit(batchId, tenantId, userId, plan.itemId(), objectKey);
        if (confirmation.known() && confirmation.recorded()) {
            log.warn("Bulk import batch {}: the commit of staged object {} reported {}, but the row is recorded - "
                    + "the upload succeeded", batchId, objectKey, failure.toString());
            return new ImportFileReceivedResponse(confirmation.rowNumber(), confirmation.filesReceived(),
                    confirmation.totalRecords());
        }
        if (confirmation.known()) {
            discardAttempt(storage, location, objectKey, failure);
            throw failure;
        }
        log.error("Bulk import batch {}: could not tell whether staged object {} (item {}) was recorded after the "
                + "commit failed - the object is KEPT for reconciliation, not deleted", batchId, objectKey,
                plan.itemId(), failure);
        com.oak.dms.importexport.exception.BulkImportException uncertain =
                new com.oak.dms.importexport.exception.BulkImportException(HttpStatus.SERVICE_UNAVAILABLE,
                        "IMPORT_FILE_STATE_UNCERTAIN", "The upload could not be confirmed. The file was kept; check "
                        + "the import's status before uploading it again.");
        uncertain.initCause(failure);
        throw uncertain;
    }

    private Confirmation confirmCommit(UUID batchId, UUID tenantId, UUID userId, UUID itemId, String objectKey) {
        try {
            return inTransaction(status -> {
                // The same lock as the transaction being checked: if its commit is still in flight, this waits for it.
                ImportBatch batch = importBatchRepository
                        .lockByImportBatchIdAndTenantIdAndCreatedBy(batchId, tenantId, userId).orElse(null);
                ImportItem item = batch == null ? null : importItemRepository.findById(itemId).orElse(null);
                if (item == null) {
                    return new Confirmation(false, false, 0, 0, 0);
                }
                boolean recorded = objectKey.equals(item.getStagedObjectKey());
                return new Confirmation(recorded, true, recorded ? (int) importItemRepository.countReceived(batchId) : 0,
                        item.getRowNumber(), batch.getTotalRecords());
            });
        } catch (RuntimeException e) {
            log.error("Bulk import batch {}: could not look up item {} to confirm staged object {}", batchId,
                    itemId, objectKey, e);
            return new Confirmation(false, false, 0, 0, 0);
        }
    }

    private ImportBatchSizeException batchSizeExceeded() {
        return new ImportBatchSizeException("IMPORT_BATCH_SIZE_EXCEEDED",
                "Adding this file would take the import over its " + properties.getMaxBatchSize().toMegabytes()
                        + "MB limit.");
    }

    private <T> T inTransaction(org.springframework.transaction.support.TransactionCallback<T> work) {
        return new TransactionTemplate(transactionManager).execute(work);
    }

    /** The upload's I/O must never run inside a transaction: it would hold that transaction's connection. */
    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("receiveFile must not run inside a transaction: its storage and "
                    + "virus-scan I/O would hold the transaction's database connection");
        }
    }

    @Override
    @Transactional
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
    @Transactional
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
                batch.getSuccessCount(), batch.getFailureCount(), missing, batch.getCreatedAt(),
                ocrProgress(batch));
    }

    private BulkImportStatusResponse toStatusResponse(ImportBatch batch, List<ImportItem> items) {
        // A file handed on to the scan gate has left staging but was received.
        List<String> missingFilenames = items.stream()
                .filter(item -> item.getStagedObjectKey() == null && item.getScanResultId() == null)
                .map(ImportItem::getFilenameReference)
                .toList();
        return new BulkImportStatusResponse(batch.getImportBatchId(), batch.getDocumentTypeId(),
                batch.getStatus().name(), batch.getTotalRecords(), items.size() - missingFilenames.size(),
                batch.getSuccessCount(), batch.getFailureCount(), missingFilenames, batch.getCreatedAt(),
                ocrProgress(batch));
    }

    /**
     * "OCR pending / done / failed" for the import's progress display.
     *
     * <p>Null for a batch that did not ask for OCR, so the screen shows
     * nothing rather than a row of zeroes that looks like stalled work.
     *
     * <p>Counted from {@code document_versions}, the only place that knows
     * what actually happened: counting {@code processing_jobs} would lose a
     * finished job that has been cleaned up, and counting the batch's own rows
     * would say nothing about OCR at all. One grouped query, not one per row.
     */
    private BulkImportStatusResponse.OcrProgress ocrProgress(ImportBatch batch) {
        String requested = batch.getOcrRequestedFields();
        if (requested == null || requested.isBlank() || "[]".equals(requested.trim())) {
            return null;
        }
        Map<String, Integer> byStatus = importItemRepository.countOcrStatusesForBatch(batch.getImportBatchId())
                .stream().collect(Collectors.toMap(
                        row -> String.valueOf(row[0]), row -> ((Number) row[1]).intValue()));
        int completed = byStatus.getOrDefault("completed", 0);
        int failed = byStatus.getOrDefault("failed", 0);
        int pending = byStatus.getOrDefault("pending", 0) + byStatus.getOrDefault("processing", 0);
        List<String> skippedFilenames = importItemRepository.findOcrSkippedFilenames(batch.getImportBatchId(),
                org.springframework.data.domain.PageRequest.of(0, OCR_SKIPPED_FILENAMES_LIMIT));
        int skippedTotal = skippedFilenames.size() < OCR_SKIPPED_FILENAMES_LIMIT
                ? skippedFilenames.size()
                : (int) importItemRepository.countOcrSkippedFilenames(batch.getImportBatchId());
        return new BulkImportStatusResponse.OcrProgress(batch.getOcrQueuedCount(), pending, completed, failed,
                skippedFilenames, skippedTotal);
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

    /**
     * The batch's "Run OCR on imported files" selection, as the JSON array the
     * column and the queue message both carry.
     *
     * <p>Validated here, at batch creation, against the document type's
     * OCR-extractable fields - not per file in the worker. A typo in the field
     * list is the uploader's mistake and they are standing right there; the
     * alternative is 5,000 files each queued for a field that does not exist,
     * discovered hours later in a log nobody reads.
     *
     * <p>An unknown or non-extractable key is a 400, not a silent drop: a
     * person who asked for "invoice_total" and got nothing back would conclude
     * OCR is broken, which is a worse answer than being told the field is not
     * one OCR can fill.
     */
    private String ocrFieldsJson(DocumentType documentType, List<String> ocrFields) {
        if (ocrFields == null || ocrFields.isEmpty()) {
            return null;
        }
        Set<String> extractable = documentTypeFieldService.getSchema(documentType.getDocumentTypeId()).fields().stream()
                .filter(DocumentTypeSchemaResponse.SchemaField::ocrExtractable)
                .map(DocumentTypeSchemaResponse.SchemaField::fieldKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        // LinkedHashSet on the way in too: the same key listed twice is one
        // request, and the order the person chose is the order worth keeping.
        Set<String> requested = new LinkedHashSet<>(ocrFields);
        List<String> unknown = requested.stream().filter(key -> !extractable.contains(key)).toList();
        if (!unknown.isEmpty()) {
            throw com.oak.dms.importexport.exception.BulkImportException.badRequest("IMPORT_OCR_FIELDS_INVALID",
                    "These fields can't be filled in by OCR for '" + documentType.getTypeName() + "': "
                            + String.join(", ", unknown));
        }
        try {
            return objectMapper.writeValueAsString(requested);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("Failed to serialize the import's OCR field selection", e);
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

    private static com.oak.dms.importexport.exception.BulkImportException sizeMismatch(String filename,
                                                                                         long declaredBytes) {
        return com.oak.dms.importexport.exception.BulkImportException.badRequest("IMPORT_FILE_SIZE_MISMATCH",
                "'" + filename + "' is not the size its header says (" + declaredBytes + " bytes), so it was not "
                        + "staged. Check or recreate the file or ZIP and upload it again.");
    }

    /**
     * Removes the object of an attempt that is known not to have been recorded: written partly (the file-system adapter
     * leaves a partial file when its stream throws), refused by a check, or rolled back. The key is unique to this
     * attempt, so nothing accepted earlier - or by a concurrent attempt - can be deleted by this. A delete that fails is
     * logged as an error and attached to the exception being thrown - an orphaned object must not go unnoticed. Never
     * call this when the item may have committed.
     */
    private static void discardAttempt(ObjectStorageService storage, StorageLocation location, String objectKey,
                                       Exception cause) {
        try {
            storage.delete(location, objectKey);
        } catch (RuntimeException cleanupFailure) {
            log.error("Could not remove the unrecorded staged import object {} at storage location {} - it is "
                    + "orphaned and has to be removed by hand", objectKey, location.getStorageLocationId(),
                    cleanupFailure);
            cause.addSuppressed(cleanupFailure);
        }
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
