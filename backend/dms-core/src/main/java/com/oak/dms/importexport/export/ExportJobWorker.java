package com.oak.dms.importexport.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.common.io.TempFiles;
import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.importexport.config.ExportJobProperties;
import com.oak.dms.importexport.dto.CreateExportRequest;
import com.oak.dms.importexport.entity.ExportJob;
import com.oak.dms.importexport.enums.ExportJobStatus;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.folder.FolderAccessLevel;
import com.oak.dms.folder.FolderAccessService;
import com.oak.dms.importexport.repository.ExportJobRepository;
import com.oak.dms.security.auth.JobIdentity;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StoragePurpose;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import com.oak.dms.storage.service.StorageLocationResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Produces an export in the background: claims the job (pending ->
 * processing), works out which documents it covers, builds the ZIP
 * ({@link ExportArchiveBuilder}) in a local temp file as the requester,
 * then stores it in the tenant's staging location at
 * {@code {tenantId}/tmp/exports/{jobId}/export.zip} - never the document
 * location, and never served directly: the only way out is the download
 * endpoint, which re-checks ownership and expiry.
 * <p>
 * Runs on {@code applicationTaskExecutor}. A job interrupted by a restart
 * is started again from scratch on startup (its ZIP is simply rebuilt).
 */
@Slf4j
@Component
public class ExportJobWorker {

    private static final Set<ExportJobStatus> UNFINISHED = EnumSet.of(ExportJobStatus.pending, ExportJobStatus.processing);

    private final ExportJobRepository repository;
    private final ExportDocumentSelector selector;
    private final ExportArchiveBuilder builder;
    private final StorageLocationResolver storageLocationResolver;
    private final ObjectStorageServiceFactory objectStorageServiceFactory;
    private final AuditService auditService;
    private final ExportJobProperties properties;
    private final ObjectMapper objectMapper;
    private final TaskExecutor taskExecutor;
    private final TransactionTemplate transactionTemplate;
    private final JobIdentity jobIdentity;
    private final FolderAccessService folderAccess;
    private final DocumentAccessService documentAccess;
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();

    public ExportJobWorker(ExportJobRepository repository,
                           ExportDocumentSelector selector,
                           ExportArchiveBuilder builder,
                           StorageLocationResolver storageLocationResolver,
                           ObjectStorageServiceFactory objectStorageServiceFactory,
                           AuditService auditService,
                           ExportJobProperties properties,
                           ObjectMapper objectMapper,
                           @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor,
                           PlatformTransactionManager transactionManager,
                           JobIdentity jobIdentity,
                           FolderAccessService folderAccess,
                           DocumentAccessService documentAccess) {
        this.repository = repository;
        this.selector = selector;
        this.builder = builder;
        this.storageLocationResolver = storageLocationResolver;
        this.objectStorageServiceFactory = objectStorageServiceFactory;
        this.auditService = auditService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.taskExecutor = taskExecutor;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.jobIdentity = jobIdentity;
        this.folderAccess = folderAccess;
        this.documentAccess = documentAccess;
    }

    /** Queues the job; returns at once. Call only once the job row is committed. */
    public void start(UUID jobId) {
        if (!running.add(jobId)) {
            return;
        }
        try {
            taskExecutor.execute(() -> {
                try {
                    run(jobId);
                } finally {
                    running.remove(jobId);
                }
            });
        } catch (RuntimeException e) {
            running.remove(jobId);
            log.error("Could not queue export job {}", jobId, e);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resumeInterrupted() {
        try {
            repository.findByStatusIn(UNFINISHED).forEach(job -> {
                log.info("Resuming export job {}", job.getExportJobId());
                start(job.getExportJobId());
            });
        } catch (RuntimeException e) {
            log.error("Could not resume interrupted export jobs", e);
        }
    }

    void run(UUID jobId) {
        ExportJob job = claim(jobId);
        if (job == null) {
            return;
        }
        // As the requester, with their access as it is now - not as it was when they asked.
        if (!jobIdentity.bind(job.getTenantId(), job.getRequestedBy())) {
            fail(jobId, "The person who requested this export is no longer an active user.");
            return;
        }
        Path temp = null;
        try {
            CreateExportRequest request = withAccessNow(
                    objectMapper.readValue(job.getRequestParams(), CreateExportRequest.class));
            if (request == null) {
                fail(jobId, "You no longer have access to that folder, or it was deleted.");
                return;
            }
            List<UUID> documentIds = selector.select(job.getTenantId(), request, properties.getMaxDocuments() + 1);
            if (documentIds.isEmpty()) {
                fail(jobId, "None of the requested documents exist any more.");
                return;
            }
            if (documentIds.size() > properties.getMaxDocuments()) {
                fail(jobId, "More than " + properties.getMaxDocuments() + " documents match - narrow the export.");
                return;
            }
            temp = TempFiles.create("dms-export-", ".zip");
            ExportArchiveBuilder.Result result = builder.build(job.getTenantId(), documentIds,
                    request.byFolder() ? request.folderId() : null, request.filesIncluded(), job.getMetadataFormat(),
                    temp, properties.getMaxExportSize().toBytes());

            StorageLocation location = storageLocationResolver.resolveForTenant(job.getTenantId(), StoragePurpose.staging);
            String objectKey = job.getTenantId() + "/tmp/exports/" + jobId + "/export.zip";
            long size = Files.size(temp);
            try (InputStream content = Files.newInputStream(temp)) {
                objectStorageServiceFactory.resolve(location).upload(location, objectKey, content, size, "application/zip");
            }
            complete(jobId, location, objectKey, size, result, documentIds);
        } catch (ExportArchiveBuilder.ExportTooLargeException e) {
            fail(jobId, e.getMessage());
        } catch (JsonProcessingException e) {
            log.error("Export job {} has unreadable request_params", jobId, e);
            fail(jobId, "The export request could not be read.");
        } catch (IOException | RuntimeException e) {
            log.error("Export job {} failed", jobId, e);
            fail(jobId, "The export could not be produced. Try again.");
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    log.warn("Could not delete export temp file {}", temp, e);
                }
            }
            TenantContext.clear();
        }
    }

    /**
     * The request with access applied again, as the requester now: what was hidden from them when they
     * asked stays out, and so does anything hidden from them since. Null when the folder being
     * exported is now out of their reach (or gone).
     */
    private CreateExportRequest withAccessNow(CreateExportRequest request) {
        if (request.byFolder()) {
            try {
                folderAccess.require(request.folderId(), FolderAccessLevel.view);
            } catch (ResourceNotFoundException e) {
                return null;
            }
        }
        DocumentAccessService.DocumentAccessView now = documentAccess.forCaller();
        Set<UUID> hiddenFolders = new HashSet<>(request.hiddenFolders());
        hiddenFolders.addAll(now.folders().hiddenFolderIds());
        Set<UUID> hiddenDocuments = new HashSet<>(request.hiddenDocuments());
        hiddenDocuments.addAll(documentAccess.hiddenRestrictedIds(now));
        return request.withHidden(hiddenFolders, hiddenDocuments);
    }

    /** pending -> processing (or picks a processing one back up after a restart); null if there's nothing to do. */
    private ExportJob claim(UUID jobId) {
        return transactionTemplate.execute(status -> repository.lockById(jobId)
                .filter(job -> UNFINISHED.contains(job.getStatus()))
                .map(job -> {
                    OffsetDateTime now = OffsetDateTime.now();
                    job.setStatus(ExportJobStatus.processing);
                    job.setStartedAt(now);
                    job.setUpdatedAt(now);
                    return repository.save(job);
                })
                .orElse(null));
    }

    private void complete(UUID jobId, StorageLocation location, String objectKey, long size,
                          ExportArchiveBuilder.Result result, List<UUID> documentIds) {
        ExportJob done = transactionTemplate.execute(status -> {
            ExportJob job = repository.lockById(jobId).orElseThrow();
            OffsetDateTime now = OffsetDateTime.now();
            job.setStatus(ExportJobStatus.completed);
            job.setStorageLocationId(location.getStorageLocationId());
            job.setFileObjectKey(objectKey);
            job.setFileSizeBytes(size);
            job.setDocumentCount(result.documentCount());
            job.setDocumentIds(documentIds.toArray(UUID[]::new));
            job.setCompletedAt(now);
            job.setExpiresAt(now.plus(properties.getJobTtl()));
            job.setFailureReason(result.filesSkipped() > 0
                    ? result.filesSkipped() + " file(s) could not be included - see the Note column." : null);
            job.setUpdatedAt(now);
            return repository.save(job);
        });
        audit(done, AuditOutcome.SUCCESS);
        log.info("Export job {} completed: {} documents, {} bytes", jobId, result.documentCount(), size);
    }

    private void fail(UUID jobId, String reason) {
        ExportJob failed = transactionTemplate.execute(status -> {
            ExportJob job = repository.lockById(jobId).orElseThrow();
            OffsetDateTime now = OffsetDateTime.now();
            job.setStatus(ExportJobStatus.failed);
            job.setFailureReason(reason.length() > 1000 ? reason.substring(0, 1000) : reason);
            job.setCompletedAt(now);
            job.setUpdatedAt(now);
            return repository.save(job);
        });
        audit(failed, AuditOutcome.FAILURE);
    }

    private void audit(ExportJob job, AuditOutcome outcome) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", job.getStatus().name());
        details.put("exportType", job.getExportType().name());
        details.put("documentCount", job.getDocumentCount());
        details.put("fileSizeBytes", job.getFileSizeBytes());
        details.put("correlationId", job.getCorrelationId());
        var event = auditService.event(AuditActionCatalog.EXPORT_COMPLETED)
                .tenantId(job.getTenantId())
                .objectType(AuditObjectTypes.EXPORT_JOB)
                .objectId(job.getExportJobId())
                .outcome(outcome)
                .newValue(details);
        if (outcome == AuditOutcome.FAILURE && job.getFailureReason() != null) {
            event.failureReason(job.getFailureReason());
        }
        event.log();
    }
}
