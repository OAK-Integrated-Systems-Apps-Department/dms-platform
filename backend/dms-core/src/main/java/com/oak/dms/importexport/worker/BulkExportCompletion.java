package com.oak.dms.importexport.worker;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.importexport.config.ImportExportProperties;
import com.oak.dms.importexport.entity.ExportJob;
import com.oak.dms.importexport.entity.ExportJob.ExportJobStatus;
import com.oak.dms.importexport.repository.ExportJobRepository;
import com.oak.dms.importexport.service.impl.BulkExportServiceImpl;
import com.oak.dms.outbox.OutboxEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Gives an export job its final status - completed by {@link BulkExportWorker},
 * failed by the worker or by {@link BulkExportCleanupJob} for a stuck job -
 * and announces it the same way whoever does it: {@code export_job.completed}
 * through the outbox (for the notification module; {@code status} in the
 * payload says which) and a {@code BULK_EXPORT_COMPLETED} audit event from
 * the system actor (outcome FAILURE with the reason for a failed job).
 * Callers hold the job's row lock and run this in their transaction.
 */
@Component
@RequiredArgsConstructor
public class BulkExportCompletion {

    private final ExportJobRepository exportJobRepository;
    private final OutboxEventPublisher outboxEventPublisher;
    private final AuditService auditService;
    private final ImportExportProperties properties;

    /** The ZIP is at {@code fileObjectKey} in {@code fileStorageLocationId}, downloadable until the TTL runs out. */
    public void markCompleted(ExportJob job, int documentCount, int skippedCount, UUID fileStorageLocationId,
                              String fileObjectKey, long fileSizeBytes) {
        OffsetDateTime now = OffsetDateTime.now();
        job.setStatus(ExportJobStatus.completed);
        job.setDocumentCount(documentCount);
        job.setSkippedCount(skippedCount);
        job.setFileStorageLocationId(fileStorageLocationId);
        job.setFileObjectKey(fileObjectKey);
        job.setFileSizeBytes(fileSizeBytes);
        job.setFailureReason(null);
        job.setCompletedAt(now);
        job.setExpiresAt(now.plusHours(properties.getExportFileTtlHours()));
        job.setUpdatedAt(now);
        exportJobRepository.save(job);
        announce(job, AuditOutcome.SUCCESS);
    }

    public void markFailed(ExportJob job, String reason, int skippedCount) {
        OffsetDateTime now = OffsetDateTime.now();
        job.setStatus(ExportJobStatus.failed);
        job.setDocumentCount(0);
        job.setSkippedCount(skippedCount);
        job.setFailureReason(reason);
        job.setCompletedAt(now);
        job.setUpdatedAt(now);
        exportJobRepository.save(job);
        announce(job, AuditOutcome.FAILURE);
    }

    private void announce(ExportJob job, AuditOutcome outcome) {
        outboxEventPublisher.publish(job.getTenantId(), BulkExportServiceImpl.AGGREGATE_TYPE, job.getExportJobId(),
                BulkExportServiceImpl.COMPLETED_EVENT_TYPE, completedPayload(job));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", job.getStatus().name());
        details.put("requestedBy", job.getCreatedBy());
        details.put("documentCount", job.getDocumentCount());
        details.put("skippedCount", job.getSkippedCount());
        details.put("metadataFormat", job.getMetadataFormat() != null ? job.getMetadataFormat().name() : null);
        details.put("fileSizeBytes", job.getFileSizeBytes());
        auditService.event(AuditActionCatalog.BULK_EXPORT_COMPLETED)
                .tenantId(job.getTenantId())
                .systemActor()
                .objectType(AuditObjectTypes.EXPORT_JOB)
                .objectId(job.getExportJobId())
                .outcome(outcome)
                .failureReason(outcome == AuditOutcome.FAILURE ? job.getFailureReason() : null)
                .newValue(details)
                .log();
    }

    /**
     * Payload of {@code export_job.completed}: who to tell and what
     * happened. {@code requestedBy} is null if the requester was deleted.
     */
    static Map<String, Object> completedPayload(ExportJob job) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("exportJobId", job.getExportJobId());
        payload.put("tenantId", job.getTenantId());
        payload.put("requestedBy", job.getCreatedBy());
        payload.put("status", job.getStatus().name());
        payload.put("documentCount", job.getDocumentCount());
        payload.put("skippedCount", job.getSkippedCount());
        payload.put("correlationId", job.getCorrelationId());
        return payload;
    }
}
