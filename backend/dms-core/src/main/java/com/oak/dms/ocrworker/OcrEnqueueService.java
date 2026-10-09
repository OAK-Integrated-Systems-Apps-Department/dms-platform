package com.oak.dms.ocrworker;

import com.oak.dms.outbox.OutboxEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The single place an OCR job is ever queued.
 *
 * <h2>Why it exists</h2>
 * The OCR branch inserted the {@code processing_jobs} row and published
 * {@code document.version.ocr.requested} inline in its upload method. That was
 * fine when upload was the only way a version appeared. It isn't any more:
 * versions are created by {@code DocumentVersionPromoter} - once, for any
 * source - and OCR must start there and nowhere else, so that a file that
 * never passed the scan gate can never be read by the worker.
 *
 * <h2>Why it can decline</h2>
 * {@link #enqueue} returns false rather than throwing when it won't queue. A
 * bulk import of 5,000 files must not fail because OCR is busy, and the person
 * who uploaded one document must not wait behind that import. Three reasons to
 * decline, all of them recoverable:
 * <ul>
 *   <li>{@code dms.ocr.enabled=false} - the deployment doesn't run OCR at all.
 *       Nothing is published and uploads never depend on the worker.</li>
 *   <li>the per-tenant queue limit is reached;</li>
 *   <li>the caller's own cap (a bulk-import batch) is reached.</li>
 * </ul>
 * In every declining case the version keeps {@code ocr_status = 'pending'} and
 * its {@code requested_fields}, with no live job row. That pair IS the durable
 * marker - {@code OcrBacklogSweeper} finds it later and tries again. Nothing is
 * lost by declining, which is what makes declining safe.
 *
 * <h2>Priority</h2>
 * Interactive scans and bulk-import files go to different routing keys so a
 * large import can't starve someone watching a progress bar.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OcrEnqueueService {

    static final String EVENT_TYPE_OCR_REQUESTED = "document.version.ocr.requested";
    static final String AGGREGATE_TYPE_DOCUMENT_VERSION = "document_version";

    /**
     * Low-priority variant, consumed from its own queue. Same payload and
     * schema - only the routing differs, so ocr-worker needs no new contract,
     * just a second binding.
     */
    static final String EVENT_TYPE_OCR_REQUESTED_BULK = "document.version.ocr.requested.bulk";

    private final ProcessingJobRepository processingJobRepository;
    private final OutboxEventPublisher outboxEventPublisher;
    private final OcrProperties ocrProperties;
    private final OcrJobTokenCodec jobTokenCodec;

    /** Where the request came from - decides the queue it lands on and the cap that applies. */
    public enum Source {
        /** Someone is watching: Scan Documents, or a re-run from the review screen. */
        interactive,
        /** A Bulk Import batch: capped per batch and queued at low priority. */
        bulk_import
    }

    /**
     * Queues OCR for a version that already exists and is already clean.
     *
     * @return true if a job was queued; false if it was declined and left for
     *         the backlog sweeper (see the class note - never an error)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean enqueue(UUID tenantId, UUID documentId, UUID versionId, UUID correlationId, Source source) {
        if (!ocrProperties.isEnabled()) {
            log.debug("OCR is disabled - not queueing version {}", versionId);
            return false;
        }
        if (isAlreadyActive(versionId)) {
            // Re-run of a version whose job is still queued or processing. Not
            // a decline and not a second job: the existing one will pick up the
            // widened requested_fields when it runs.
            log.debug("OCR job already active for version {} - not queueing a second", versionId);
            return true;
        }
        long inFlight = processingJobRepository.countActiveOcrJobsForTenant(tenantId);
        if (inFlight >= ocrProperties.getMaxQueuedPerTenant()) {
            log.info("OCR queue for tenant {} is at its limit ({}) - version {} held back for the sweeper",
                    tenantId, ocrProperties.getMaxQueuedPerTenant(), versionId);
            return false;
        }

        UUID jobId = UUID.randomUUID();
        processingJobRepository.save(ProcessingJob.builder()
                .jobId(jobId)
                .tenantId(tenantId)
                .jobType(ProcessingJob.JOB_TYPE_OCR)
                .referenceType(ProcessingJob.REFERENCE_TYPE_DOCUMENT_VERSION)
                .referenceId(versionId)
                .status(ProcessingJob.STATUS_QUEUED)
                .attempts(0)
                .correlationId(correlationId)
                .build());

        // LinkedHashMap, not Map.of: correlation_id must serialize as a real
        // JSON null when absent (Map.of throws on a null value), to match
        // ocr-worker's own build_ocr_requested_payload contract exactly
        // (docs/events/ocr-scanning-events.md).
        String eventType = source == Source.bulk_import ? EVENT_TYPE_OCR_REQUESTED_BULK : EVENT_TYPE_OCR_REQUESTED;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema_version", 1);
        // The worker matches on event_type, so the payload always names the
        // canonical one; the bulk variant exists for ROUTING only.
        payload.put("event_type", EVENT_TYPE_OCR_REQUESTED);
        payload.put("tenant_id", tenantId.toString());
        payload.put("document_id", documentId.toString());
        payload.put("version_id", versionId.toString());
        payload.put("correlation_id", correlationId != null ? correlationId.toString() : null);
        payload.put("occurred_at", OffsetDateTime.now().toString());
        // The job id and its read grant travel with the event. The worker has
        // no other way to obtain them, and minting the grant here - rather
        // than letting the worker ask for one - means there is no endpoint
        // that hands out grants, only one that honours them.
        payload.put("job_id", jobId.toString());
        Instant issuedAt = Instant.now();
        payload.put("read_token", jobTokenCodec.issueForJob(jobId, versionId, tenantId, issuedAt,
                issuedAt.plus(Duration.ofMinutes(ocrProperties.getJobTokenMinutes()))));
        outboxEventPublisher.publish(tenantId, AGGREGATE_TYPE_DOCUMENT_VERSION, versionId, eventType, payload);
        return true;
    }

    private boolean isAlreadyActive(UUID versionId) {
        return processingJobRepository
                .findTopByReferenceTypeAndReferenceIdAndJobTypeOrderByCreatedAtDesc(
                        ProcessingJob.REFERENCE_TYPE_DOCUMENT_VERSION, versionId, ProcessingJob.JOB_TYPE_OCR)
                .filter(job -> ProcessingJob.STATUS_QUEUED.equals(job.getStatus())
                        || ProcessingJob.STATUS_PROCESSING.equals(job.getStatus()))
                .isPresent();
    }
}
