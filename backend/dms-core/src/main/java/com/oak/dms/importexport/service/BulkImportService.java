package com.oak.dms.importexport.service;

import com.oak.dms.importexport.dto.BulkImportCreatedResponse;
import com.oak.dms.importexport.dto.BulkImportStatusResponse;
import com.oak.dms.importexport.dto.ImportDocumentTypeOption;
import com.oak.dms.importexport.dto.ImportFileReceivedResponse;
import com.oak.dms.importexport.dto.ImportRowFailure;
import com.oak.dms.importexport.dto.ImportTemplateFile;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.importexport.config.BulkImportProperties;
import com.oak.dms.importexport.dto.ImportPreviewResponse;
import com.oak.dms.importexport.dto.MigrationBatchResponse;
import com.oak.dms.importexport.dto.MigrationItemResponse;
import com.oak.dms.importexport.entity.MigrationBatch;
import com.oak.dms.importexport.entity.MigrationItem;
import com.oak.dms.importexport.entity.UploadSession;
import com.oak.dms.importexport.enums.MigrationItemStatus;
import com.oak.dms.importexport.enums.TemplateFormat;
import com.oak.dms.importexport.enums.UploadSessionStatus;
import com.oak.dms.importexport.exception.BulkImportException;
import com.oak.dms.importexport.repository.MigrationBatchRepository;
import com.oak.dms.importexport.repository.MigrationItemRepository;
import com.oak.dms.importexport.repository.UploadSessionRepository;
import com.oak.dms.importexport.template.ImportTemplateReader;
import com.oak.dms.importexport.template.ImportTemplateWriter;
import com.oak.dms.importexport.template.ParsedTemplate;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse;
import com.oak.dms.metadata.entity.DocumentType;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.metadata.service.DocumentTypeFieldService;
import com.oak.dms.security.auth.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Bulk import intake (Module 2.1): template download, manifest upload,
 * per-file staging, and submit. Everything here is scoped to the caller's
 * tenant, and a batch is only visible to the user who created it. Field
 * validation and record creation happen later, in the import worker.
 */
@Slf4j
@Service
public interface BulkImportService {

    /** Active, non-deleted document types in the caller's tenant, in display order - the template picker's options. */
    List<ImportDocumentTypeOption> listDocumentTypes();

    /** An .xlsx template for the document type's current schema version. */
    ImportTemplateFile generateTemplate(UUID documentTypeId);

    /** Creates a batch in {@code uploading} with one item per non-empty template row. */
    BulkImportCreatedResponse createBatch(long templateSize, InputStream template);

    /** Stages one file against the lowest-numbered unfilled row that lists its filename. */
    ImportFileReceivedResponse receiveFile(UUID batchId, String originalFilename, String contentType,
                                           long contentLength, InputStream content);

    /** Moves the batch to {@code queued} and publishes {@code import_batch.submitted}. */
    void submit(UUID batchId);

    BulkImportStatusResponse getStatus(UUID batchId);

    /**
     * The batch's failed rows in row order, each with its problems and a
     * display label for the column each problem is about (resolved against
     * the batch's own schema version).
     */
    List<ImportRowFailure> getFailures(UUID batchId);

    /** The caller's batch counting toward the active-batch limit (uploading, queued or running), if any. */
    Optional<BulkImportStatusResponse> getActive();

    /**
     * Moves an {@code uploading} or {@code queued} batch to {@code cancelled}
     * and deletes its staged files once the transaction commits.
     */
    BulkImportStatusResponse cancel(UUID batchId);
  


/**
 * Template-based bulk import (Module 2.1), request side:
 * <ul>
 *   <li>B1 - {@link #template}: an xlsx/csv template generated from the
 *   document type's current schema;</li>
 *   <li>{@link #preview}: the template's rows validated, nothing imported;</li>
 *   <li>B2 - {@link #start}: template + files (a ZIP, loose files, or a
 *   completed resumable upload session) validated row by row, saved as a
 *   batch, and handed to {@link BulkImportWorker};</li>
 *   <li>B3 - {@link #failedRows}: the failed rows back as a template with
 *   their reasons; fixed, it is started again with {@code resubmitOf} so
 *   files already uploaded are reused.</li>
 * </ul>
 * Batches are private to the user who started them, like exports.
 * Needs {@link TenantContext}.
 */



    /** Largest template accepted - the rows, not the files. */
    static final long MAX_TEMPLATE_BYTES = 20L * 1024 * 1024;
    static final int MAX_IDEMPOTENCY_KEY = 100;

    public record TemplateFile(String fileName, String contentType, byte[] content) {
    }

    /** @param created false when an idempotent retry returned the batch it had already created */
    public record StartResult(MigrationBatchResponse batch, boolean created) {
    }

    private final MigrationBatchRepository batchRepository;
    private final MigrationItemRepository itemRepository;
    private final UploadSessionRepository uploadSessionRepository;
    private final DocumentTypeRepository documentTypeRepository;
    private final DocumentTypeFieldService documentTypeFieldService;
    private final ImportTemplateWriter templateWriter;
    private final ImportTemplateReader templateReader;
    private final BulkImportValidator validator;
    private final BulkImportRowProcessor rowProcessor;
    private final BulkImportWorker worker;
    private final BulkImportAuditor auditor;
    private final ImportStagingArea stagingArea;
    private final BulkImportProperties properties;
    private final TransactionTemplate transactionTemplate;

    public BulkImportService(MigrationBatchRepository batchRepository,
                             MigrationItemRepository itemRepository,
                             UploadSessionRepository uploadSessionRepository,
                             DocumentTypeRepository documentTypeRepository,
                             DocumentTypeFieldService documentTypeFieldService,
                             ImportTemplateWriter templateWriter,
                             ImportTemplateReader templateReader,
                             BulkImportValidator validator,
                             BulkImportRowProcessor rowProcessor,
                             BulkImportWorker worker,
                             BulkImportAuditor auditor,
                             ImportStagingArea stagingArea,
                             BulkImportProperties properties,
                             PlatformTransactionManager transactionManager) {
        this.batchRepository = batchRepository;
        this.itemRepository = itemRepository;
        this.uploadSessionRepository = uploadSessionRepository;
        this.documentTypeRepository = documentTypeRepository;
        this.documentTypeFieldService = documentTypeFieldService;
        this.templateWriter = templateWriter;
        this.templateReader = templateReader;
        this.validator = validator;
        this.rowProcessor = rowProcessor;
        this.worker = worker;
        this.auditor = auditor;
        this.stagingArea = stagingArea;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------ B1 template

    public TemplateFile template(UUID documentTypeId, TemplateFormat format) {
        DocumentType type = requireImportableType(documentTypeId);
        DocumentTypeSchemaResponse schema = documentTypeFieldService.getSchema(documentTypeId);
        byte[] content = templateWriter.writeTemplate(format, schema);
        return new TemplateFile(safeFilePart(type.getTypeCode()) + "-import-template-v" + schema.schemaVersion()
                + "." + format.name(), format.contentType(), content);
    }

    // --------------------------------------------------------------- preview

    public ImportPreviewResponse preview(MultipartFile templateFile) {
        ParsedTemplate template = readTemplate(templateFile);
        requireImportableType(template.documentTypeId());
        DocumentTypeSchemaResponse schema = documentTypeFieldService.getSchema(template.documentTypeId());
        validator.checkTemplate(template, schema);
        List<BulkImportValidator.RowResult> results = validator.validateRows(template, schema, null);
        List<ImportPreviewResponse.ProblemRow> problems = results.stream()
                .filter(r -> !r.valid())
                .limit(ImportPreviewResponse.MAX_LISTED)
                .map(r -> new ImportPreviewResponse.ProblemRow(r.row().rowNumber(), r.reference(), r.errors()))
                .toList();
        int invalid = (int) results.stream().filter(r -> !r.valid()).count();
        return new ImportPreviewResponse(schema.documentTypeId(), schema.typeName(), schema.schemaVersion(),
                results.size(), results.size() - invalid, invalid, problems);
    }

    // --------------------------------------------------------------- B2 start

    /**
     * @param archive         a ZIP of the files (at most the multipart limit; use an upload session for larger)
     * @param files           loose files, instead of or as well as a ZIP
     * @param uploadSessionId a completed resumable upload of the ZIP, instead of {@code archive}
     * @param resubmitOf      an earlier batch whose files are reused for any file not uploaded again
     */
    public StartResult start(MultipartFile templateFile, MultipartFile archive, List<MultipartFile> files,
                             UUID uploadSessionId, UUID resubmitOf, String idempotencyKey) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        ImportActor actor = ImportActor.current();
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
        if (key != null && key.length() > MAX_IDEMPOTENCY_KEY) {
            throw BulkImportException.badRequest("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be at most " + MAX_IDEMPOTENCY_KEY + " characters.");
        }
        if (key != null) {
            var existing = batchRepository.findByTenantIdAndCreatedByAndIdempotencyKeyAndDeletedFalse(tenantId, userId, key);
            if (existing.isPresent()) {
                return new StartResult(toResponse(existing.get()), false);
            }
        }
        List<MultipartFile> looseFiles = files == null ? List.of() : files.stream().filter(f -> !f.isEmpty()).toList();
        boolean hasArchive = archive != null && !archive.isEmpty();
        if (hasArchive && uploadSessionId != null) {
            throw BulkImportException.badRequest("TOO_MANY_ARCHIVES", "Send the ZIP either directly or as an upload session, not both.");
        }
        if (!hasArchive && uploadSessionId == null && looseFiles.isEmpty() && resubmitOf == null) {
            throw BulkImportException.badRequest("NO_FILES", "Upload the files the template lists - a ZIP or the files themselves.");
        }
        if (hasArchive && !String.valueOf(archive.getOriginalFilename()).toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw BulkImportException.badRequest("INVALID_ARCHIVE", "The archive must be a .zip file.");
        }

        ParsedTemplate template = readTemplate(templateFile);
        requireImportableType(template.documentTypeId());
        DocumentTypeSchemaResponse schema = documentTypeFieldService.getSchema(template.documentTypeId());
        validator.checkTemplate(template, schema);

        MigrationBatch original = resubmitOf == null ? null : ownBatch(resubmitOf);
        if (original != null && !original.getDocumentTypeId().equals(template.documentTypeId())) {
            throw BulkImportException.badRequest("RESUBMIT_TYPE_MISMATCH",
                    "The template is for a different document type than the import it resubmits.");
        }
        UploadSession session = uploadSessionId == null ? null : completedSession(uploadSessionId);
        Path sessionFile = session == null ? null : stagingArea.sessionFile(session.getUploadSessionId());

        Path incoming = stagingArea.newIncomingDir();
        boolean sessionAdopted = false;
        try {
            if (hasArchive) {
                stagingArea.stageArchive(incoming, archive);
            }
            if (session != null) {
                stagingArea.adoptSessionFile(incoming, sessionFile);
                sessionAdopted = true;
            }
            if (!looseFiles.isEmpty()) {
                stagingArea.stageLooseFiles(incoming, looseFiles);
            }
            if (original != null) {
                stagingArea.linkToOriginal(incoming, original.getMigrationBatchId());
            }

            List<BulkImportValidator.RowResult> results;
            try (ImportFiles importFiles = ImportFiles.open(stagingArea.fileDirs(incoming), properties.getMaxArchiveEntries())) {
                if (importFiles.isEmpty()) {
                    throw BulkImportException.badRequest("NO_FILES", original != null
                            ? "The files of the import being resubmitted are no longer kept - upload them again."
                            : "The uploaded ZIP holds no files.");
                }
                results = validator.validateRows(template, schema, importFiles);
            }

            MigrationBatch batch = save(tenantId, userId, key, template, results, session);
            auditor.started(batch, templateFile.getOriginalFilename());
            try {
                stagingArea.moveToBatch(incoming, batch.getMigrationBatchId());
            } catch (RuntimeException e) {
                log.error("Bulk import batch {}: staged files could not be moved into place", batch.getMigrationBatchId(), e);
                stagingArea.deleteQuietly(incoming);
                rowProcessor.failBatch(batch.getMigrationBatchId(), "The uploaded files could not be stored. Try again.")
                        .ifPresent(auditor::finished);
                return new StartResult(get(batch.getMigrationBatchId()), true);
            }

            if (results.stream().anyMatch(BulkImportValidator.RowResult::valid)) {
                worker.start(batch.getMigrationBatchId(), actor);
            } else {
                rowProcessor.finishIfDone(batch.getMigrationBatchId()).ifPresent(auditor::finished);
            }
            return new StartResult(toResponse(batchRepository.findById(batch.getMigrationBatchId()).orElseThrow()), true);
        } catch (DataIntegrityViolationException raced) {
            restoreSession(sessionAdopted, incoming, sessionFile);
            stagingArea.deleteQuietly(incoming);
            // The same Idempotency-Key raced another request that won.
            if (key != null) {
                var winner = batchRepository.findByTenantIdAndCreatedByAndIdempotencyKeyAndDeletedFalse(tenantId, userId, key);
                if (winner.isPresent()) {
                    return new StartResult(toResponse(winner.get()), false);
                }
            }
            throw raced;
        } catch (RuntimeException e) {
            restoreSession(sessionAdopted, incoming, sessionFile);
            stagingArea.deleteQuietly(incoming);
            throw e;
        }
    }

    private MigrationBatch save(UUID tenantId, UUID userId, String key, ParsedTemplate template,
                                List<BulkImportValidator.RowResult> results, UploadSession session) {
        int invalid = (int) results.stream().filter(r -> !r.valid()).count();
        return transactionTemplate.execute(status -> {
            OffsetDateTime now = OffsetDateTime.now();
            MigrationBatch batch = batchRepository.saveAndFlush(MigrationBatch.builder()
                    .tenantId(tenantId)
                    .documentTypeId(template.documentTypeId())
                    .schemaVersion(template.schemaVersion())
                    .templateFormat(template.format())
                    .idempotencyKey(key)
                    .totalRecords(results.size())
                    .failureCount(invalid)
                    .createdBy(userId)
                    .createdAt(now)
                    .build());
            List<MigrationItem> items = new ArrayList<>(results.size());
            for (BulkImportValidator.RowResult result : results) {
                String reference = result.reference() == null ? "" : result.reference();
                items.add(MigrationItem.builder()
                        .tenantId(tenantId)
                        .migrationBatchId(batch.getMigrationBatchId())
                        .sourceReference(reference.length() > 500 ? reference.substring(0, 500) : reference)
                        .rowNumber(result.row().rowNumber())
                        .rowData(result.row().cells())
                        .status(result.valid() ? MigrationItemStatus.pending : MigrationItemStatus.failed)
                        .validationResult(result.valid() ? null : String.join("\n", result.errors()))
                        .createdAt(now)
                        .updatedAt(result.valid() ? null : now)
                        .build());
            }
            itemRepository.saveAll(items);
            if (session != null) {
                UploadSession used = uploadSessionRepository.findById(session.getUploadSessionId()).orElseThrow();
                used.setDeleted(true);
                used.setDeletedAt(now);
                used.setUpdatedAt(now);
                used.setUpdatedBy(userId);
                used.setStagingPath(null);
            }
            return batch;
        });
    }

    private void restoreSession(boolean adopted, Path incoming, Path sessionFile) {
        if (!adopted) {
            return;
        }
        try {
            Files.move(incoming.resolve(ImportFiles.ARCHIVE), sessionFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("Could not return upload session file to {}: {}", sessionFile, e.getMessage());
        }
    }

    // ----------------------------------------------------------- read / cancel

    public Page<MigrationBatchResponse> listMine(Pageable pageable) {
        Page<MigrationBatch> page = batchRepository.findByTenantIdAndCreatedByAndDeletedFalse(
                TenantContext.getTenantId(), TenantContext.getUserId(), pageable);
        Map<UUID, String> names = typeNames(page.getContent().stream().map(MigrationBatch::getDocumentTypeId).toList());
        return page.map(b -> toResponse(b, names.get(b.getDocumentTypeId())));
    }

    public MigrationBatchResponse get(UUID batchId) {
        return toResponse(ownBatch(batchId));
    }

    public Page<MigrationItemResponse> items(UUID batchId, String status, Pageable pageable) {
        MigrationBatch batch = ownBatch(batchId);
        Page<MigrationItem> page = status == null || status.isBlank()
                ? itemRepository.findByMigrationBatchIdAndTenantId(batch.getMigrationBatchId(), batch.getTenantId(), pageable)
                : itemRepository.findByMigrationBatchIdAndTenantIdAndStatus(batch.getMigrationBatchId(),
                        batch.getTenantId(), itemStatus(status), pageable);
        return page.map(BulkImportService::toItemResponse);
    }

    /**
     * Rows not yet started are cancelled; a row whose file is already being
     * scanned still finishes (its upload can't be taken back part way).
     */
    public MigrationBatchResponse cancel(UUID batchId) {
        MigrationBatch batch = ownBatch(batchId);
        if (batch.getStatus().isFinished()) {
            throw BulkImportException.conflict("IMPORT_FINISHED", "This import has already finished.");
        }
        transactionTemplate.executeWithoutResult(status -> batchRepository.lockById(batchId).ifPresent(locked -> {
            locked.setCancelRequested(true);
            locked.setUpdatedAt(OffsetDateTime.now());
            locked.setUpdatedBy(TenantContext.getUserId());
        }));
        if (!worker.isRunning(batchId)) {
            // Nothing is working through the rows (not started yet, or only
            // scans outstanding): cancel them here.
            rowProcessor.cancelUnstarted(batchId);
            rowProcessor.finishIfDone(batchId).ifPresent(auditor::finished);
        }
        return get(batchId);
    }

    // ------------------------------------------------------ B3 failed rows

    /** The failed and cancelled rows as a correctable template, each with why it wasn't imported. */
    public TemplateFile failedRows(UUID batchId, TemplateFormat requestedFormat) {
        MigrationBatch batch = ownBatch(batchId);
        List<MigrationItem> rows = new ArrayList<>();
        rows.addAll(itemRepository.findByMigrationBatchIdAndStatusOrderByRowNumberAsc(batchId, MigrationItemStatus.failed));
        rows.addAll(itemRepository.findByMigrationBatchIdAndStatusOrderByRowNumberAsc(batchId, MigrationItemStatus.cancelled));
        if (rows.isEmpty()) {
            throw new BulkImportException(HttpStatus.NOT_FOUND, "NO_FAILED_ROWS", "This import has no failed rows.");
        }
        rows.sort((a, b) -> Integer.compare(a.getRowNumber(), b.getRowNumber()));
        // Always the current schema: a report taken after the fields changed
        // is a template that can still be uploaded.
        DocumentTypeSchemaResponse schema = documentTypeFieldService.getSchema(batch.getDocumentTypeId());
        TemplateFormat format = requestedFormat != null ? requestedFormat : batch.getTemplateFormat();
        List<ImportTemplateWriter.RowOut> out = rows.stream()
                .map(i -> new ImportTemplateWriter.RowOut(i.getRowData(),
                        i.getValidationResult() == null ? "" : i.getValidationResult().replace("\n", "; ")))
                .toList();
        String name = safeFilePart(schema.typeCode()) + "-failed-rows-" + batchId.toString().substring(0, 8)
                + "." + format.name();
        return new TemplateFile(name, format.contentType(), templateWriter.writeRows(format, schema, out));
    }

    // --------------------------------------------------------------- helpers

    private ParsedTemplate readTemplate(MultipartFile templateFile) {
        if (templateFile == null || templateFile.isEmpty()) {
            throw BulkImportException.badRequest("NO_TEMPLATE", "Attach the filled-in template.");
        }
        if (templateFile.getSize() > MAX_TEMPLATE_BYTES) {
            throw BulkImportException.badRequest("TEMPLATE_TOO_LARGE", "The template is larger than 20 MB.");
        }
        try {
            return templateReader.read(templateFile.getOriginalFilename(), templateFile.getBytes(), properties.getMaxRows());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded template", e);
        }
    }

    private DocumentType requireImportableType(UUID documentTypeId) {
        DocumentType type = documentTypeRepository
                .findByDocumentTypeIdAndTenantIdAndDeletedFalse(documentTypeId, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("DocumentType", documentTypeId));
        if (type.getStatus() != DocumentType.DocumentTypeStatus.active) {
            throw BulkImportException.conflict("DOCUMENT_TYPE_INACTIVE",
                    "\"" + type.getTypeName() + "\" is inactive, so documents can't be imported into it.");
        }
        return type;
    }

    private MigrationBatch ownBatch(UUID batchId) {
        return batchRepository.findByMigrationBatchIdAndTenantIdAndDeletedFalse(batchId, TenantContext.getTenantId())
                .filter(b -> b.getCreatedBy().equals(TenantContext.getUserId()))
                .orElseThrow(() -> new ResourceNotFoundException("Import", batchId));
    }

    private UploadSession completedSession(UUID uploadSessionId) {
        UploadSession session = uploadSessionRepository
                .findByUploadSessionIdAndTenantIdAndInitiatedByAndDeletedFalse(uploadSessionId,
                        TenantContext.getTenantId(), TenantContext.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("UploadSession", uploadSessionId));
        if (session.getStatus() != UploadSessionStatus.completed
                || !Files.isRegularFile(stagingArea.sessionFile(uploadSessionId))) {
            throw BulkImportException.conflict("UPLOAD_INCOMPLETE",
                    "The ZIP upload hasn't finished yet (" + session.getReceivedBytes() + " of "
                            + session.getTotalSizeBytes() + " bytes received).");
        }
        return session;
    }

    private static MigrationItemStatus itemStatus(String value) {
        return Arrays.stream(MigrationItemStatus.values())
                .filter(s -> s.name().equals(value))
                .findFirst()
                .orElseThrow(() -> BulkImportException.badRequest("INVALID_STATUS", "status must be one of: "
                        + Arrays.stream(MigrationItemStatus.values()).map(Enum::name).collect(Collectors.joining(", "))));
    }

    private Map<UUID, String> typeNames(List<UUID> ids) {
        UUID tenantId = TenantContext.getTenantId();
        return documentTypeRepository.findAllById(ids.stream().distinct().toList()).stream()
                .filter(t -> tenantId.equals(t.getTenantId()))
                .collect(Collectors.toMap(DocumentType::getDocumentTypeId, DocumentType::getTypeName));
    }

    private MigrationBatchResponse toResponse(MigrationBatch batch) {
        return toResponse(batch, typeNames(List.of(batch.getDocumentTypeId())).get(batch.getDocumentTypeId()));
    }

    private MigrationBatchResponse toResponse(MigrationBatch b, String typeName) {
        long pending = b.getStatus().isFinished()
                ? 0
                : itemRepository.countByMigrationBatchIdAndStatus(b.getMigrationBatchId(), MigrationItemStatus.pending);
        return new MigrationBatchResponse(b.getMigrationBatchId(), b.getDocumentTypeId(), typeName, b.getSchemaVersion(),
                b.getTemplateFormat(), b.getStatus(), b.getTotalRecords(), b.getSuccessCount(), b.getFailureCount(),
                pending, b.isCancelRequested(), b.getFailureReason(), b.getCreatedAt(), b.getStartedAt(),
                b.getCompletedAt());
    }

    private static MigrationItemResponse toItemResponse(MigrationItem i) {
        List<String> errors = i.getValidationResult() == null || i.getValidationResult().isBlank()
                ? List.of()
                : List.of(i.getValidationResult().split("\n"));
        return new MigrationItemResponse(i.getMigrationItemId(), i.getRowNumber(), i.getSourceReference(),
                BulkImportValidator.titleFor(i.getRowData()), i.getStatus(), i.getDocumentId(), errors,
                i.getStatus() == MigrationItemStatus.pending && i.getScanResultId() != null);
    }

    private static String safeFilePart(String value) {
        String cleaned = value == null ? "" : value.replaceAll("[^A-Za-z0-9_-]", "-");
        return cleaned.isBlank() ? "documents" : cleaned;
    }

}
