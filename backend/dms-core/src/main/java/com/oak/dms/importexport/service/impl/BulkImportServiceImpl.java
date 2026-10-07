package com.oak.dms.importexport.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ApiException;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.importexport.config.ImportExportProperties;
import com.oak.dms.importexport.dto.BulkImportCreatedResponse;
import com.oak.dms.importexport.dto.BulkImportStatusResponse;
import com.oak.dms.importexport.dto.BulkImportSummary;
import com.oak.dms.importexport.dto.ImportDefaultsResponse;
import com.oak.dms.importexport.dto.ImportDocumentTypeOption;
import com.oak.dms.importexport.dto.ImportFailuresCsvFile;
import com.oak.dms.importexport.dto.ImportFileReceivedResponse;
import com.oak.dms.importexport.dto.ImportRowFailure;
import com.oak.dms.importexport.dto.ImportRowProblem;
import com.oak.dms.importexport.entity.ImportBatch;
import com.oak.dms.importexport.entity.ImportBatch.ImportBatchStatus;
import com.oak.dms.importexport.entity.ImportItem;
import com.oak.dms.importexport.exception.ActiveImportBatchLimitException;
import com.oak.dms.importexport.exception.ImportBatchNotCancellableException;
import com.oak.dms.importexport.exception.ImportBatchSizeException;
import com.oak.dms.importexport.exception.ImportBatchStateException;
import com.oak.dms.importexport.exception.InactiveDocumentTypeException;
import com.oak.dms.importexport.exception.InvalidImportFileException;
import com.oak.dms.importexport.repository.ImportBatchRepository;
import com.oak.dms.importexport.repository.ImportItemRepository;
import com.oak.dms.importexport.service.BulkImportService;
import com.oak.dms.importexport.storage.StagedImportFiles;
import com.oak.dms.importexport.validation.ImportDefaults;
import com.oak.dms.importexport.validation.ImportField;
import com.oak.dms.importexport.validation.ImportFilenames;
import com.oak.dms.importexport.validation.ImportRowError;
import com.oak.dms.importexport.validation.ImportSchemaFields;
import com.oak.dms.importexport.validation.ImportTitles;
import com.oak.dms.metadata.entity.DocumentType;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
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
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * See {@link BulkImportService}. Staged files go through
 * {@link ObjectStorageServiceFactory}/{@link StorageLocationResolver} only,
 * under a server-generated key ({@code imports/{tenantId}/{batchId}/{itemId}}) -
 * never the uploaded filename. They are unscanned, so they wait in the
 * tenant's {@link StoragePurpose#staging} location, never the document
 * location - see docs/scanning-architecture.md. Each file's type is checked
 * by {@link FileTypeInspector} at intake, the same check the scan gate runs,
 * so a bad file is rejected before anything is stored - and still saved as
 * a failed item, so it shows up in the import's results.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class BulkImportServiceImpl implements BulkImportService {

    public static final String AGGREGATE_TYPE = "import_batch";
    public static final String SUBMITTED_EVENT_TYPE = "import_batch.submitted";
    static final String FILE_REJECTED = "FILE_REJECTED";

    private static final List<ImportBatchStatus> ACTIVE_STATUSES =
            List.of(ImportBatchStatus.uploading, ImportBatchStatus.queued, ImportBatchStatus.running);
    private static final List<ImportBatchStatus> CANCELLABLE_STATUSES =
            List.of(ImportBatchStatus.uploading, ImportBatchStatus.queued);
    /** New items have no values of their own - their metadata is the type's defaults. */
    private static final String NO_ROW_VALUES = "{}";
    static final int MAX_LIST_PAGE_SIZE = 50;
    private static final Sort NEWEST_FIRST =
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("importBatchId"));
    private static final Map<String, String> OWN_FIELD_LABELS = Map.of(
            "file", "File",
            "title", "Title");
    private static final TypeReference<List<ImportRowError>> ROW_ERRORS = new TypeReference<>() {
    };
    /** What the browser may send as a clientFileId: a UUID fits, so does any short token. */
    private static final Pattern CLIENT_FILE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final String FILE_TYPE_NOT_ALLOWED = "FILE_TYPE_NOT_ALLOWED";
    private static final DateTimeFormatter CSV_FILENAME_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private final ImportBatchRepository importBatchRepository;
    private final ImportItemRepository importItemRepository;
    private final DocumentTypeRepository documentTypeRepository;
    private final StorageLocationResolver storageLocationResolver;
    private final ObjectStorageServiceFactory objectStorageServiceFactory;
    private final StagedImportFiles stagedImportFiles;
    private final OutboxEventPublisher outboxEventPublisher;
    private final ImportExportProperties properties;
    private final ObjectMapper objectMapper;
    private final FileTypeInspector fileTypeInspector;
    private final ImportSchemaFields importSchemaFields;
    private final ImportDefaults importDefaults;
    private final AuditService auditService;

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
    public ImportDefaultsResponse getDefaults(UUID documentTypeId) {
        UUID tenantId = TenantContext.getTenantId();
        DocumentType documentType = activeDocumentType(documentTypeId, tenantId);
        int schemaVersion = documentType.getCurrentSchemaVersion();

        // The same resolution the worker runs for a batch at this version.
        ImportDefaults.Resolved resolved = importDefaults.resolve(
                importSchemaFields.load(tenantId, documentTypeId, schemaVersion), tenantId, documentTypeId,
                schemaVersion);
        return new ImportDefaultsResponse(documentTypeId, schemaVersion,
                resolved.applied().stream()
                        .map(applied -> new ImportDefaultsResponse.DefaultValue(applied.field().fieldKey(),
                                applied.field().label(), applied.rawValue()))
                        .toList(),
                resolved.invalidDefaults().stream()
                        .map(invalid -> new ImportDefaultsResponse.InvalidDefault(invalid.field().fieldKey(),
                                invalid.field().label(), invalid.rawValue(), invalid.reason()))
                        .toList(),
                resolved.missingMandatory().stream()
                        .map(field -> new ImportDefaultsResponse.Field(field.fieldKey(), field.label()))
                        .toList(),
                properties.isEnforceMandatoryFields());
    }

    @Override
    public BulkImportCreatedResponse createBatch(UUID documentTypeId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();

        DocumentType documentType = activeDocumentType(documentTypeId, tenantId);
        int schemaVersion = documentType.getCurrentSchemaVersion();

        long activeBatches = importBatchRepository
                .countByTenantIdAndCreatedByAndStatusInAndDeletedFalse(tenantId, userId, ACTIVE_STATUSES);
        if (activeBatches >= properties.getMaxActiveBatchesPerUser()) {
            throw new ActiveImportBatchLimitException(
                    "You already have a bulk import in progress. Wait for it to finish before starting another.");
        }

        if (properties.isEnforceMandatoryFields()) {
            List<ImportField> missing = importDefaults.resolve(
                    importSchemaFields.load(tenantId, documentTypeId, schemaVersion), tenantId, documentTypeId,
                    schemaVersion).missingMandatory();
            if (!missing.isEmpty()) {
                throw new ImportBatchSizeException("IMPORT_MANDATORY_FIELDS_WITHOUT_DEFAULT",
                        "'" + documentType.getTypeName() + "' has required fields with no usable default value: "
                                + ImportDefaults.labels(missing) + ". Give them default values before importing.");
            }
        }

        ImportBatch batch = importBatchRepository.save(ImportBatch.builder()
                .tenantId(tenantId)
                .sourceType(ImportBatch.SourceType.bulk_import)
                .documentTypeId(documentTypeId)
                .schemaVersion(schemaVersion)
                .status(ImportBatchStatus.uploading)
                .totalRecords(0)
                .correlationId(UUID.randomUUID())
                .createdBy(userId)
                .build());

        log.info("Created bulk import batch {} for document type {} v{} in tenant {}", batch.getImportBatchId(),
                documentTypeId, schemaVersion, tenantId);
        return new BulkImportCreatedResponse(batch.getImportBatchId(), documentTypeId, schemaVersion,
                properties.getMaxFilesPerBatch());
    }

    /*
     * A refused file (type/content, empty, password-protected, batch size)
     * is saved as a failed item and the exception rethrown. Those two
     * exceptions commit rather than roll back so the item survives the
     * failed request. It's the same transaction that holds the batch lock,
     * so it can't deadlock on it or wait for a second connection, and
     * nothing else has been written by the time either is thrown. Every
     * other failure still rolls back - the item with it.
     */
    @Override
    @Transactional(noRollbackFor = {ImportBatchSizeException.class, FileTypeNotAllowedException.class})
    public ImportFileReceivedResponse receiveFile(UUID batchId, String clientFileId, String originalFilename,
                                                  String contentType, long contentLength, InputStream content) {
        if (clientFileId != null && !CLIENT_FILE_ID.matcher(clientFileId).matches()) {
            throw new InvalidImportFileException("IMPORT_CLIENT_FILE_ID_INVALID",
                    "The upload's clientFileId must be 1-64 letters, digits, '-' or '_'.");
        }
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();

        // Held until commit: serializes uploads into the same batch so two
        // requests can't take the same row number or both slip under a limit.
        ImportBatch batch = importBatchRepository.lockByImportBatchIdAndTenantIdAndCreatedBy(batchId, tenantId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("ImportBatch", batchId));
        requireUploading(batch);

        // Never the client's folder path - not in the item, not in messages.
        String filename = ImportFilenames.strip(originalFilename);
        if (filename.isEmpty()) {
            throw new InvalidImportFileException("IMPORT_FILE_NAME_MISSING", "The uploaded file has no filename.");
        }

        List<ImportItem> items = importItemRepository.findAllByImportBatchIdOrderByRowNumberAsc(batchId);
        // A retry of an upload this batch already has (FIE-NFR-004): answer
        // as the first time, before anything is written or staged.
        if (clientFileId != null) {
            Optional<ImportItem> earlier = items.stream()
                    .filter(item -> clientFileId.equals(item.getClientFileId()))
                    .findFirst();
            if (earlier.isPresent()) {
                return replay(earlier.get(), items, clientFileId);
            }
        }
        if (items.size() >= properties.getMaxFilesPerBatch()) {
            throw new ImportBatchSizeException("IMPORT_TOO_MANY_FILES", "An import can take at most "
                    + properties.getMaxFilesPerBatch() + " files. Submit this one and start another for the rest.");
        }
        int rowNumber = items.stream().mapToInt(ImportItem::getRowNumber).max().orElse(0) + 1;
        batch.setTotalRecords(items.size() + 1);
        batch.setUpdatedBy(userId);
        batch.setUpdatedAt(OffsetDateTime.now());
        importBatchRepository.save(batch);

        BufferedInputStream buffered;
        DetectedFileType detected;
        try {
            long stagedBytes = items.stream()
                    .map(ImportItem::getStagedSizeBytes)
                    .filter(Objects::nonNull)
                    .mapToLong(Long::longValue)
                    .sum();
            if (stagedBytes + contentLength > properties.getMaxBatchSize().toBytes()) {
                throw new ImportBatchSizeException("IMPORT_BATCH_SIZE_EXCEEDED",
                        "Adding this file would take the import over its "
                                + properties.getMaxBatchSize().toMegabytes() + "MB limit.");
            }

            // Type check on the head of the stream, as the scan gate does - a
            // rejected file stages nothing. The client's Content-Type is never
            // trusted; the inspector's canonical MIME type is stored instead.
            buffered = new BufferedInputStream(content, FileTypeInspector.HEAD_BYTES * 2);
            byte[] head = peek(buffered);
            detected = fileTypeInspector.inspect(filename, head, head.length);
            if (detected.encrypted()) {
                // The gate would mark it unscannable anyway - reject it now.
                throw new FileTypeNotAllowedException("Password-protected files can't be scanned for malware. "
                        + "Remove the password and upload the file again.");
            }
        } catch (ImportBatchSizeException | FileTypeNotAllowedException e) {
            // Saved (committed - see noRollbackFor above) so it appears in the
            // results; the caller still gets e.
            recordRejection(batchId, tenantId, rowNumber, filename, clientFileId, e);
            throw e;
        }

        ImportItem item = importItemRepository.save(newItem(batchId, tenantId, rowNumber, filename, clientFileId));

        StorageLocation location = storageLocationResolver.resolveForTenant(tenantId, StoragePurpose.staging);
        String objectKey = buildStagedObjectKey(tenantId, batchId, item.getImportItemId());
        ObjectStorageService storage = objectStorageServiceFactory.resolve(location);

        // A storage failure rolls back the item too, so nothing half-made is
        // left behind.
        StoredObjectMetadata stored = storage.upload(location, objectKey, buffered, contentLength,
                detected.mimeType());
        deleteOnRollback(storage, location, objectKey);

        item.setStagedObjectKey(objectKey);
        item.setStagedStorageLocationId(location.getStorageLocationId());
        item.setStagedSizeBytes(stored.sizeBytes());
        item.setUpdatedAt(OffsetDateTime.now());
        importItemRepository.save(item);

        log.info("Staged file as row {} of bulk import batch {} ({} files)", rowNumber, batchId, items.size() + 1);
        return receivedResponse(rowNumber, filename, items);
    }

    /** The response for an accepted file added as {@code rowNumber} after {@code before}. */
    private static ImportFileReceivedResponse receivedResponse(int rowNumber, String filename,
                                                               List<ImportItem> before) {
        int rejected = (int) before.stream().filter(BulkImportServiceImpl::isRejected).count();
        int received = before.size() - rejected + 1;
        return new ImportFileReceivedResponse(rowNumber, filename, received, rejected, before.size() + 1);
    }

    /**
     * The original outcome of an upload being retried: the response it got
     * (counted over the rows before it, as it was then - rows are only ever
     * appended), or its rejection thrown again with the same status, code
     * and message. Writes nothing.
     */
    private static ImportFileReceivedResponse replay(ImportItem earlier, List<ImportItem> items,
                                                     String clientFileId) {
        log.debug("Replayed upload {} as row {} of bulk import batch {}", clientFileId, earlier.getRowNumber(),
                earlier.getImportBatchId());
        String filename = ImportFilenames.strip(earlier.getFilenameReference());
        if (isRejected(earlier)) {
            String stored = earlier.getUploadRejectionMessage();
            String prefix = rejectionPrefix(filename);
            String message = stored != null && stored.startsWith(prefix) ? stored.substring(prefix.length()) : stored;
            String code = earlier.getUploadRejectionCode();
            throw FILE_TYPE_NOT_ALLOWED.equals(code)
                    ? new FileTypeNotAllowedException(message)
                    : new ImportBatchSizeException(code, message);
        }
        List<ImportItem> before = items.stream()
                .filter(item -> item.getRowNumber() < earlier.getRowNumber())
                .toList();
        return receivedResponse(earlier.getRowNumber(), filename, before);
    }

    @Override
    public void submit(UUID batchId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();

        ImportBatch batch = importBatchRepository.lockByImportBatchIdAndTenantIdAndCreatedBy(batchId, tenantId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("ImportBatch", batchId));
        requireUploading(batch);

        List<ImportItem> items = importItemRepository.findAllByImportBatchIdOrderByRowNumberAsc(batchId);
        long rejected = items.stream().filter(BulkImportServiceImpl::isRejected).count();
        if (items.size() == rejected) {
            throw new ImportBatchSizeException("IMPORT_NO_ACCEPTED_FILES",
                    "Upload at least one file that can be imported before submitting.");
        }

        // Rejected uploads are already failed items; the worker leaves them
        // as they are and they count toward the results.
        batch.setStatus(ImportBatchStatus.queued);
        batch.setTotalRecords(items.size());
        batch.setUpdatedBy(userId);
        batch.setUpdatedAt(OffsetDateTime.now());
        importBatchRepository.save(batch);

        // A queued batch can still be cancelled (see cancel); the worker
        // re-checks status == queued under the batch lock before it starts.
        outboxEventPublisher.publish(tenantId, AGGREGATE_TYPE, batchId, SUBMITTED_EVENT_TYPE,
                submittedPayload(batch));

        auditService.event(AuditActionCatalog.BULK_IMPORT_STARTED)
                .tenantId(tenantId)
                .actor(userId, TenantContext.getDisplayNameOrNull())
                .objectType(AuditObjectTypes.IMPORT_BATCH)
                .objectId(batchId)
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of(
                        "documentTypeId", batch.getDocumentTypeId(),
                        "totalRecords", batch.getTotalRecords(),
                        "rejectedFiles", rejected))
                .log();

        log.info("Submitted bulk import batch {} for tenant {} ({} files, {} rejected)", batchId, tenantId,
                items.size(), rejected);
    }

    /** Payload of {@value #SUBMITTED_EVENT_TYPE} - also re-published by {@code ImportBatchRecoveryJob}. */
    public static Map<String, Object> submittedPayload(ImportBatch batch) {
        return Map.of("importBatchId", batch.getImportBatchId(), "correlationId", batch.getCorrelationId());
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
        return failuresOf(batch, tenantId);
    }

    // NOT readOnly: the BULK_IMPORT_FAILED_ROWS_DOWNLOADED write below joins
    // this transaction (see AuditService#persist's @Transactional) - a
    // readOnly transaction would make Postgres reject that insert.
    @Override
    @Transactional
    public ImportFailuresCsvFile exportFailuresCsv(UUID batchId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();

        ImportBatch batch = importBatchRepository
                .findByImportBatchIdAndTenantIdAndCreatedByAndDeletedFalse(batchId, tenantId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("ImportBatch", batchId));
        ImportFailuresCsv.Written csv = ImportFailuresCsv.write(failuresOf(batch, tenantId));

        // Type codes are upper-case letters, digits and '_', so the name needs
        // no encoding. A type deleted since keeps the report downloadable.
        String typeCode = documentTypeRepository
                .findByDocumentTypeIdAndTenantIdAndDeletedFalse(batch.getDocumentTypeId(), tenantId)
                .map(DocumentType::getTypeCode)
                .orElse("import");
        String filename = typeCode + "-import-failures-" + CSV_FILENAME_TIMESTAMP.format(OffsetDateTime.now())
                + ".csv";

        auditService.event(AuditActionCatalog.BULK_IMPORT_FAILED_ROWS_DOWNLOADED)
                .tenantId(tenantId)
                .actor(userId, TenantContext.getDisplayNameOrNull())
                .objectType(AuditObjectTypes.IMPORT_BATCH)
                .objectId(batchId)
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of(
                        "rowCount", csv.rowCount(),
                        "format", "csv"))
                .log();

        return new ImportFailuresCsvFile(filename, ImportFailuresCsv.withBom(csv.text()));
    }

    /** The batch's failed items as the failures report shows them. */
    private List<ImportRowFailure> failuresOf(ImportBatch batch, UUID tenantId) {
        UUID batchId = batch.getImportBatchId();
        List<ImportItem> failed = importItemRepository.findAllByImportBatchIdAndStatusOrderByRowNumberAsc(batchId,
                ImportItem.ImportItemStatus.failed);
        if (failed.isEmpty()) {
            return List.of();
        }

        // Labels come from the batch's own schema version, the one its
        // defaults (and so its error field keys) came from.
        Map<String, ImportField> schemaFields = importSchemaFields.load(tenantId, batch.getDocumentTypeId(),
                batch.getSchemaVersion());
        return failed.stream()
                .map(item -> {
                    // Stripped here too, for rows saved before uploads were.
                    String filename = ImportFilenames.strip(item.getFilenameReference());
                    return new ImportRowFailure(item.getRowNumber(), filename, ImportTitles.fromFilename(filename),
                            toProblems(rowErrors(item), schemaFields));
                })
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
                batch.getSuccessCount(), batch.getFailureCount(), batch.getCreatedAt(), batch.getCompletedAt(),
                cancelReason(batch)));
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

        ImportBatchStatus previousStatus = batch.getStatus();
        batch.setStatus(ImportBatchStatus.cancelled);
        batch.setCancelReason(ImportBatch.CancelReason.user);
        batch.setUpdatedBy(userId);
        batch.setUpdatedAt(OffsetDateTime.now());
        importBatchRepository.save(batch);

        List<ImportItem> items = importItemRepository.findAllByImportBatchIdOrderByRowNumberAsc(batchId);
        int toDelete = stagedImportFiles.clearAndDeleteAfterCommit(items);
        importItemRepository.saveAll(items);

        auditService.event(AuditActionCatalog.BULK_IMPORT_CANCELLED)
                .tenantId(tenantId)
                .actor(userId, TenantContext.getDisplayNameOrNull())
                .objectType(AuditObjectTypes.IMPORT_BATCH)
                .objectId(batchId)
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of(
                        "cancelReason", ImportBatch.CancelReason.user.name(),
                        "previousStatus", previousStatus.name()))
                .log();

        log.info("Cancelled bulk import batch {} for tenant {} ({} staged files to delete)",
                batchId, tenantId, toDelete);
        return toStatusResponse(batch, items);
    }

    private static BulkImportStatusResponse toStatusResponse(ImportBatch batch, List<ImportItem> items) {
        List<String> rejectedFilenames = items.stream()
                .filter(BulkImportServiceImpl::isRejected)
                .map(item -> ImportFilenames.strip(item.getFilenameReference()))
                .toList();
        return new BulkImportStatusResponse(batch.getImportBatchId(), batch.getDocumentTypeId(),
                batch.getStatus().name(), items.size(), items.size() - rejectedFilenames.size(),
                rejectedFilenames.size(), batch.getSuccessCount(), batch.getFailureCount(), rejectedFilenames,
                batch.getCreatedAt(), cancelReason(batch));
    }

    /** An upload refused at intake - saved as a failed item with FILE_REJECTED. */
    private static boolean isRejected(ImportItem item) {
        return item.getUploadRejectionCode() != null;
    }

    private static String cancelReason(ImportBatch batch) {
        return batch.getCancelReason() != null ? batch.getCancelReason().name() : null;
    }

    /** The type in the caller's tenant (404 if not there or deleted), refused if it isn't active. */
    private DocumentType activeDocumentType(UUID documentTypeId, UUID tenantId) {
        DocumentType documentType = documentTypeRepository
                .findByDocumentTypeIdAndTenantIdAndDeletedFalse(documentTypeId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("DocumentType", documentTypeId));
        if (documentType.getStatus() != DocumentType.DocumentTypeStatus.active) {
            throw new InactiveDocumentTypeException("Document type '" + documentType.getTypeName()
                    + "' is inactive and cannot be used for imports.");
        }
        return documentType;
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

    /** Display name of what an error is about: 2.1's own labels, then the schema's label, then the raw key. */
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

    /** An item's errors as the failures report shows them, each with its field's label. */
    private static List<ImportRowProblem> toProblems(List<ImportRowError> errors,
                                                     Map<String, ImportField> schemaFields) {
        return errors.stream()
                .map(error -> new ImportRowProblem(error.field(), fieldLabel(error.field(), schemaFields),
                        error.code(), error.message(), error.severity()))
                .toList();
    }

    // The failures report is read-only and best-effort: one unreadable row
    // is logged and shown with what's left, rather than failing the report.
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

    private static ImportItem newItem(UUID batchId, UUID tenantId, int rowNumber, String filename,
                                      String clientFileId) {
        return ImportItem.builder()
                .tenantId(tenantId)
                .importBatchId(batchId)
                .rowNumber(rowNumber)
                .filenameReference(filename)
                .clientFileId(clientFileId)
                .rowValues(NO_ROW_VALUES)
                .status(ImportItem.ImportItemStatus.pending)
                .build();
    }

    /** What a stored rejection message starts with - stripped again when a retry replays the rejection. */
    private static String rejectionPrefix(String filename) {
        return "The file you uploaded ('" + filename + "') was rejected: ";
    }

    /**
     * Saves a refused upload as a failed item, with why on it - as the upload
     * rejection columns and as its FILE_REJECTED error. Stages nothing.
     */
    private void recordRejection(UUID batchId, UUID tenantId, int rowNumber, String filename, String clientFileId,
                                 ApiException rejection) {
        String message = rejectionPrefix(filename) + rejection.getMessage();
        ImportItem item = newItem(batchId, tenantId, rowNumber, filename, clientFileId);
        item.setStatus(ImportItem.ImportItemStatus.failed);
        item.setUploadRejectionCode(rejection.getCode());
        item.setUploadRejectionMessage(message);
        item.setValidationErrors(toJson(List.of(ImportRowError.error("file", FILE_REJECTED, message))));
        item.setUpdatedAt(OffsetDateTime.now());
        importItemRepository.save(item);
        log.info("Rejected file as row {} of bulk import batch {} ({})", rowNumber, batchId, rejection.getCode());
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
}
