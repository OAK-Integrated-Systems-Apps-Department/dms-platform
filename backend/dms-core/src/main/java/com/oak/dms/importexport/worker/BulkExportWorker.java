package com.oak.dms.importexport.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.audit.filter.AuditRequestContext;
import com.oak.dms.document.DocumentNotScannedException;
import com.oak.dms.document.DocumentService;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.importexport.entity.ExportJob;
import com.oak.dms.importexport.entity.ExportJob.ExportJobStatus;
import com.oak.dms.importexport.entity.ExportJob.MetadataFormat;
import com.oak.dms.importexport.export.BulkExportAccessPolicy;
import com.oak.dms.importexport.export.BulkExportRequestParams;
import com.oak.dms.importexport.export.ExportMetadataAssembler;
import com.oak.dms.importexport.export.ExportMetadataCsvWriter;
import com.oak.dms.importexport.export.ExportMetadataTable;
import com.oak.dms.importexport.export.ExportMetadataXlsxWriter;
import com.oak.dms.importexport.export.ExportPermissionRevokedException;
import com.oak.dms.importexport.export.ExportSummary;
import com.oak.dms.importexport.export.ExportableDocumentQuery;
import com.oak.dms.importexport.export.ExportableDocumentRow;
import com.oak.dms.importexport.export.ExportedDocument;
import com.oak.dms.importexport.export.SkippedDocument;
import com.oak.dms.importexport.export.ZipEntryNames;
import com.oak.dms.importexport.messaging.BulkExportMessagingConfig;
import com.oak.dms.importexport.repository.ExportJobRepository;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StoragePurpose;
import com.oak.dms.storage.service.ObjectStorageService;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import com.oak.dms.storage.service.StorageLocationResolver;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Consumes {@code export_job.requested} and builds the export's ZIP: every
 * frozen document's current version under its original filename (numbered
 * when names repeat - see {@link ZipEntryNames}), plus the metadata file
 * (metadata.xlsx, or metadata.csv and skipped.csv). The ZIP is written to
 * a temp file, uploaded to the tenant's staging location at
 * {@code exports/<tenantId>/<jobId>.zip}, and the temp file deleted.
 *
 * <p>Runs as the requester, off any web request: {@link TenantContext} is
 * bound to them (with their display name, so the DOCUMENT_DOWNLOADED event
 * {@code DocumentService#downloadDocument} writes per document names them
 * as actor), and the job's correlation id - the one its
 * BULK_EXPORT_REQUESTED event was recorded under - is bound with
 * {@link AuditRequestContext#bindBackground} for the whole build, so those
 * events and BULK_EXPORT_COMPLETED join that sequence. Their permission is
 * re-checked first ({@link BulkExportAccessPolicy#requireUserMayExport}):
 * definitively revoked fails the job; a failed lookup is rethrown, so the
 * container retries and then dead-letters the message, and the job is left
 * processing for {@link BulkExportCleanupJob}. Each document is then
 * checked again ({@link BulkExportAccessPolicy#skipReason}) and read
 * through {@code downloadDocument}, so its own checks apply too. A
 * document that fails either is skipped with a reason, never fatal.
 *
 * <p>Short {@link TransactionTemplate} transactions only, like
 * {@link BulkImportWorker}: the claim, each heartbeat, and the finish. The
 * heartbeat bumps the job's updated_at at every stage and at least every
 * {@value #HEARTBEAT_EVERY} documents, so the cleanup job never mistakes a
 * long export that is still moving for a stuck one.
 *
 * <p>Idempotency: a redelivered message for a job already
 * {@code processing} builds it again from scratch (the ZIP is rewritten
 * under the same key). If anything else finished the job meanwhile (the
 * stuck sweep failed it), the finish sees it is no longer processing and
 * discards the new file.
 */
@Slf4j
@Component
public class BulkExportWorker {

    static final int HEARTBEAT_EVERY = 50;
    static final String ZIP_CONTENT_TYPE = "application/zip";
    static final String REASON_REQUESTER_GONE = "The user who requested this export no longer exists.";
    static final String REASON_PERMISSION_REVOKED = "The requester no longer has permission to export documents.";
    static final String REASON_BUILD_FAILED = "The export could not be built. Please try again later.";
    static final String SKIP_NOT_FOUND = "The document no longer exists";
    static final String SKIP_NOT_SCANNED = "The current version has not passed the malware scan";
    static final String SKIP_UNREADABLE = "The file's content could not be read";

    private final ExportJobRepository exportJobRepository;
    private final UserRepository userRepository;
    private final BulkExportAccessPolicy accessPolicy;
    private final ExportableDocumentQuery exportableDocumentQuery;
    private final DocumentService documentService;
    private final ExportMetadataAssembler metadataAssembler;
    private final StorageLocationResolver storageLocationResolver;
    private final ObjectStorageServiceFactory objectStorageServiceFactory;
    private final BulkExportCompletion completion;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    /** Where temp files go; null for the system default. Tests point it at a directory they can inspect. */
    private Path tempDirectory;

    public BulkExportWorker(ExportJobRepository exportJobRepository,
                            UserRepository userRepository,
                            BulkExportAccessPolicy accessPolicy,
                            ExportableDocumentQuery exportableDocumentQuery,
                            DocumentService documentService,
                            ExportMetadataAssembler metadataAssembler,
                            StorageLocationResolver storageLocationResolver,
                            ObjectStorageServiceFactory objectStorageServiceFactory,
                            BulkExportCompletion completion,
                            ObjectMapper objectMapper,
                            PlatformTransactionManager transactionManager) {
        this.exportJobRepository = exportJobRepository;
        this.userRepository = userRepository;
        this.accessPolicy = accessPolicy;
        this.exportableDocumentQuery = exportableDocumentQuery;
        this.documentService = documentService;
        this.metadataAssembler = metadataAssembler;
        this.storageLocationResolver = storageLocationResolver;
        this.objectStorageServiceFactory = objectStorageServiceFactory;
        this.completion = completion;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    void setTempDirectory(Path tempDirectory) {
        this.tempDirectory = tempDirectory;
    }

    /**
     * What building needs from the claimed job, read while it was locked.
     *
     * @param requestedBy null if the requester has been deleted
     */
    record ClaimedExport(UUID exportJobId, UUID tenantId, UUID requestedBy, MetadataFormat format,
                         String requestParams, OffsetDateTime requestedAt, UUID correlationId) {
    }

    /** What went into the ZIP, and what was left out. */
    record Archive(List<ExportedDocument> exported, List<SkippedDocument> skipped) {
    }

    @RabbitListener(queues = BulkExportMessagingConfig.QUEUE,
            containerFactory = BulkExportMessagingConfig.LISTENER_CONTAINER_FACTORY)
    public void onMessage(Message message) {
        UUID exportJobId = parseExportJobId(message.getBody());
        claim(exportJobId).ifPresent(this::process);
    }

    /**
     * The job id from an {@code export_job.requested} body - double-encoded
     * by OutboxRelay today, a plain JSON object accepted too; see
     * {@code BulkImportWorker#parseBatchId}.
     *
     * @throws AmqpRejectAndDontRequeueException if there is no valid
     *         exportJobId - sent to the DLQ without retrying
     */
    UUID parseExportJobId(byte[] body) {
        try {
            JsonNode payload = objectMapper.readTree(body);
            if (payload != null && payload.isTextual()) {
                payload = objectMapper.readTree(payload.asText());
            }
            JsonNode id = payload != null ? payload.get("exportJobId") : null;
            if (id == null || !id.isTextual()) {
                throw new IllegalArgumentException("no exportJobId");
            }
            return UUID.fromString(id.asText());
        } catch (IOException | IllegalArgumentException e) {
            log.error("Rejecting malformed {} message: {}", BulkExportMessagingConfig.ROUTING_KEY, e.getMessage());
            throw new AmqpRejectAndDontRequeueException("Malformed export_job.requested payload", e);
        }
    }

    /**
     * Locks the job and moves it from pending to processing. Returns empty -
     * nothing to build - if it's gone or already finished.
     */
    Optional<ClaimedExport> claim(UUID exportJobId) {
        return transactionTemplate.execute(status -> {
            Optional<ExportJob> locked = exportJobRepository.lockByExportJobId(exportJobId);
            if (locked.isEmpty()) {
                log.warn("Export job {} not found; skipping", exportJobId);
                return Optional.<ClaimedExport>empty();
            }
            ExportJob job = locked.get();
            OffsetDateTime now = OffsetDateTime.now();
            switch (job.getStatus()) {
                case pending -> {
                    job.setStatus(ExportJobStatus.processing);
                    job.setStartedAt(now);
                    log.info("Started building export {}", exportJobId);
                }
                case processing -> log.info("Export {} is already processing; building it again (redelivery)",
                        exportJobId);
                default -> {
                    log.info("Export {} is {}; skipping", exportJobId, job.getStatus());
                    return Optional.<ClaimedExport>empty();
                }
            }
            job.setUpdatedAt(now);
            exportJobRepository.save(job);
            return Optional.of(new ClaimedExport(job.getExportJobId(), job.getTenantId(), job.getCreatedBy(),
                    job.getMetadataFormat(), job.getRequestParams(), job.getCreatedAt(), job.getCorrelationId()));
        });
    }

    /**
     * Re-checks the requester, then builds and stores the ZIP as them, with
     * the job's correlation id bound for every audit event on the way.
     */
    void process(ClaimedExport job) {
        try (AuditRequestContext.Scope scope = AuditRequestContext.bindBackground(job.correlationId())) {
            processAsRequester(job);
        }
    }

    private void processAsRequester(ClaimedExport job) {
        Optional<User> requester = job.requestedBy() == null ? Optional.empty()
                : userRepository.findByUserIdAndTenantIdAndDeletedFalse(job.requestedBy(), job.tenantId());
        if (requester.isEmpty()) {
            // Never build as nobody: TenantContext refuses a null user, and
            // there is nobody left to download the file.
            log.warn("Requester of export {} no longer exists; failing it", job.exportJobId());
            finishFailed(job, REASON_REQUESTER_GONE, 0);
            return;
        }
        User user = requester.get();
        TenantContext.set(job.tenantId(), user.getUserId(), user.getDisplayName(), user.getKeycloakId());
        try {
            try {
                accessPolicy.requireUserMayExport(job.tenantId(), user.getUserId());
            } catch (ExportPermissionRevokedException e) {
                log.warn("Failing export {}: {}", job.exportJobId(), e.getMessage());
                finishFailed(job, REASON_PERMISSION_REVOKED, 0);
                return;
            }
            touch(job);
            build(job, user);
        } finally {
            TenantContext.clear();
        }
    }

    private void build(ClaimedExport job, User requester) {
        Path zipFile = null;
        try {
            BulkExportRequestParams params = BulkExportRequestParams.fromJson(objectMapper, job.requestParams());
            zipFile = createTempFile("bulk-export-", ".zip");
            Archive archive = writeArchive(job, requester, params, zipFile);
            if (archive.exported().isEmpty()) {
                finishFailed(job, nothingExportedReason(archive.skipped()), archive.skipped().size());
                return;
            }
            touch(job);
            StorageLocation location = storageLocationResolver.resolveForTenant(job.tenantId(), StoragePurpose.staging);
            String objectKey = objectKey(job.tenantId(), job.exportJobId());
            long size = Files.size(zipFile);
            ObjectStorageService storage = objectStorageServiceFactory.resolve(location);
            try (InputStream in = Files.newInputStream(zipFile)) {
                storage.upload(location, objectKey, in, size, ZIP_CONTENT_TYPE);
            }
            finishCompleted(job, archive, storage, location, objectKey, size);
        } catch (IOException | RuntimeException e) {
            log.error("Building export {} failed", job.exportJobId(), e);
            finishFailed(job, REASON_BUILD_FAILED, 0);
        } finally {
            deleteQuietly(zipFile);
        }
    }

    private Archive writeArchive(ClaimedExport job, User requester, BulkExportRequestParams params, Path zipFile)
            throws IOException {
        List<UUID> documentIds = params.documentIds() != null ? params.documentIds() : List.of();
        Map<UUID, ExportableDocumentRow> rows = exportableDocumentQuery.loadForExport(documentIds).stream()
                .collect(Collectors.toMap(ExportableDocumentRow::documentId, Function.identity(), (a, b) -> a));
        ZipEntryNames names = new ZipEntryNames();
        // Reserved first, so a document called metadata.xlsx becomes "metadata (2).xlsx".
        if (job.format() == MetadataFormat.csv) {
            names.reserve(ExportMetadataCsvWriter.METADATA_FILENAME);
            names.reserve(ExportMetadataCsvWriter.SKIPPED_FILENAME);
        } else {
            names.reserve(ExportMetadataXlsxWriter.METADATA_FILENAME);
        }

        List<ExportedDocument> exported = new ArrayList<>();
        List<SkippedDocument> skipped = new ArrayList<>();
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zipFile)))) {
            for (int i = 0; i < documentIds.size(); i++) {
                if (i > 0 && i % HEARTBEAT_EVERY == 0) {
                    touch(job);
                }
                UUID documentId = documentIds.get(i);
                ExportableDocumentRow row = rows.get(documentId);
                if (row == null) {
                    skipped.add(new SkippedDocument(documentId.toString(), SKIP_NOT_FOUND));
                    continue;
                }
                Optional<String> reason = accessPolicy.skipReason(row, job.tenantId());
                if (reason.isEmpty()) {
                    reason = addDocument(zip, names, row, exported);
                }
                reason.ifPresent(r -> skipped.add(new SkippedDocument(title(row), r)));
            }
            if (!exported.isEmpty()) {
                touch(job);
                writeMetadata(zip, job, requester, params, exported, skipped);
            }
        }
        return new Archive(exported, skipped);
    }

    /**
     * Reads the document's current version into a temp file first, then
     * copies it into the ZIP - so a read that fails part-way leaves no
     * half-written entry. A read failure skips the document; a failure
     * writing the ZIP itself (disk full) is thrown.
     *
     * @return why the document was skipped, or empty if it was added
     */
    private Optional<String> addDocument(ZipOutputStream zip, ZipEntryNames names, ExportableDocumentRow row,
                                         List<ExportedDocument> exported) throws IOException {
        Path part = createTempFile("bulk-export-part-", ".tmp");
        try {
            try {
                DownloadedDocument document = documentService.downloadDocument(row.documentId());
                try (InputStream in = document.content(); OutputStream out = Files.newOutputStream(part)) {
                    in.transferTo(out);
                }
            } catch (DocumentNotScannedException e) {
                return Optional.of(SKIP_NOT_SCANNED);
            } catch (IOException | RuntimeException e) {
                log.warn("Skipping document {} in an export: its content could not be read", row.documentId(), e);
                return Optional.of(SKIP_UNREADABLE);
            }
            String name = names.next(row.originalFilename() != null && !row.originalFilename().isBlank()
                    ? row.originalFilename() : row.title());
            zip.putNextEntry(new ZipEntry(name));
            Files.copy(part, zip);
            zip.closeEntry();
            exported.add(new ExportedDocument(row, name));
            return Optional.empty();
        } finally {
            deleteQuietly(part);
        }
    }

    private void writeMetadata(ZipOutputStream zip, ClaimedExport job, User requester, BulkExportRequestParams params,
                               List<ExportedDocument> exported, List<SkippedDocument> skipped) throws IOException {
        ExportMetadataTable table = metadataAssembler.build(job.tenantId(), exported);
        if (job.format() == MetadataFormat.csv) {
            putEntry(zip, ExportMetadataCsvWriter.METADATA_FILENAME, ExportMetadataCsvWriter.metadata(table));
            putEntry(zip, ExportMetadataCsvWriter.SKIPPED_FILENAME, ExportMetadataCsvWriter.skipped(skipped));
            return;
        }
        List<Map.Entry<String, String>> selection = params.selection() == null ? List.of()
                : params.selection().stream()
                .<Map.Entry<String, String>>map(line -> new AbstractMap.SimpleEntry<>(line.label(), line.value()))
                .toList();
        int requested = params.documentIds() != null ? params.documentIds().size() : 0;
        ExportSummary summary = new ExportSummary(requester.getDisplayName(), job.requestedAt(),
                OffsetDateTime.now(), "xlsx", selection, requested, exported.size(), skipped.size());
        putEntry(zip, ExportMetadataXlsxWriter.METADATA_FILENAME,
                ExportMetadataXlsxWriter.write(table, skipped, summary));
    }

    private static void putEntry(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private void finishCompleted(ClaimedExport job, Archive archive, ObjectStorageService storage,
                                 StorageLocation location, String objectKey, long size) {
        transactionTemplate.executeWithoutResult(status -> {
            deleteOnRollback(storage, location, objectKey);
            Optional<ExportJob> locked = exportJobRepository.lockByExportJobId(job.exportJobId());
            if (locked.isEmpty() || locked.get().getStatus() != ExportJobStatus.processing) {
                log.warn("Export {} is no longer processing; discarding the file just built", job.exportJobId());
                status.setRollbackOnly();
                return;
            }
            completion.markCompleted(locked.get(), archive.exported().size(), archive.skipped().size(),
                    location.getStorageLocationId(), objectKey, size);
            log.info("Completed export {} ({} documents, {} skipped, {} bytes)", job.exportJobId(),
                    archive.exported().size(), archive.skipped().size(), size);
        });
    }

    private void finishFailed(ClaimedExport job, String reason, int skippedCount) {
        transactionTemplate.executeWithoutResult(status -> {
            Optional<ExportJob> locked = exportJobRepository.lockByExportJobId(job.exportJobId());
            if (locked.isEmpty() || (locked.get().getStatus() != ExportJobStatus.pending
                    && locked.get().getStatus() != ExportJobStatus.processing)) {
                return;
            }
            completion.markFailed(locked.get(), reason, skippedCount);
            log.info("Failed export {}: {}", job.exportJobId(), reason);
        });
    }

    /** The worker's heartbeat - see {@link ExportJobRepository#touch}. */
    private void touch(ClaimedExport job) {
        transactionTemplate.executeWithoutResult(
                status -> exportJobRepository.touch(job.exportJobId(), OffsetDateTime.now()));
    }

    /** "None of the 3 selected documents could be exported (The document has been deleted: 2; ...)." */
    static String nothingExportedReason(List<SkippedDocument> skipped) {
        Map<String, Long> byReason = skipped.stream()
                .collect(Collectors.groupingBy(SkippedDocument::reason, LinkedHashMap::new, Collectors.counting()));
        String reasons = byReason.entrySet().stream()
                .map(entry -> entry.getKey() + ": " + entry.getValue())
                .collect(Collectors.joining("; "));
        return "None of the " + skipped.size() + " selected documents could be exported"
                + (reasons.isEmpty() ? "." : " (" + reasons + ").");
    }

    static String objectKey(UUID tenantId, UUID exportJobId) {
        return "exports/" + tenantId + "/" + exportJobId + ".zip";
    }

    private static String title(ExportableDocumentRow row) {
        return row.title() != null ? row.title() : row.documentId().toString();
    }

    private Path createTempFile(String prefix, String suffix) throws IOException {
        return tempDirectory != null
                ? Files.createTempFile(tempDirectory, prefix, suffix)
                : Files.createTempFile(prefix, suffix);
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Failed to delete temp file {}", file, e);
        }
    }

    /**
     * If the transaction recording the stored ZIP never commits, delete the
     * object so it isn't orphaned. Best-effort, like bulk import's.
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
                    log.warn("Failed to delete orphaned export object {}", objectKey, e);
                }
            }
        });
    }
}
