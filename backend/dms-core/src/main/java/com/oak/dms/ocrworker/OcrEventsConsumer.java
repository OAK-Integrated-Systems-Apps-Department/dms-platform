package com.oak.dms.ocrworker;

import com.oak.dms.document.DocumentMetadataService;
import com.oak.dms.document.DocumentOcrFieldResult;
import com.oak.dms.document.DocumentOcrFieldResultRepository;
import com.oak.dms.messaging.RabbitMqConfig;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * dms-core's first-ever RabbitMQ consumer (Phase 1 B.1+) -- listens for
 * {@code document.version.ocr.completed}/{@code .failed}, published by
 * ocr-worker's own outbox writer and relayed by dms-core's existing
 * {@link com.oak.dms.outbox.OutboxRelay} poller (ocr-worker shares
 * dms-core's {@code outbox_events} table/relay, it does not run a separate
 * one -- see docs/events/ocr-scanning-events.md).
 * <p>
 * ocr-worker already writes {@code document_versions.ocr_status} and
 * {@code document_ocr_field_results} directly to Postgres before publishing
 * either event (its own {@code repository.py}'s {@code complete_success}/
 * {@code complete_failure}) -- this consumer's only job on {@code .completed}
 * is to trigger {@link DocumentMetadataService#applyOcrFields}, the one
 * write path ({@code document_metadata_values}) only dms-core owns. A
 * {@code .failed} event has nothing left to do beyond logging: there is no
 * metadata to apply on failure, and {@code ocr_status}/{@code last_error}
 * are already written.
 * <p>
 * Idempotent by construction: {@code applyOcrFields} already does
 * delete-then-insert per field and protects {@code source='manual'} values
 * (see {@code DocumentMetadataServiceApplyOcrFieldsTest}) -- re-processing
 * the same {@code .completed} event twice converges to the same state, no
 * separate dedupe table needed. Staleness is checked separately: an
 * event's own {@code attempt} number is compared against the version's
 * CURRENT {@code processing_jobs.attempts}, and skipped if the event is
 * for an attempt the reaper has already superseded with a fresh retry.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OcrEventsConsumer {

    /** {@code system@system.internal.oakdms} -- the real seeded system user row (V-something), used only because
     * {@link DocumentMetadataService#applyOcrFields} runs inside {@link TenantContext}, which this
     * RabbitMQ listener thread has none of by default (no HTTP request set it). Confirmed unused
     * anywhere in applyOcrFields' own call chain beyond being present -- this is not attributed to
     * a human action, just a non-null placeholder matching the pattern the old triggerOcrAsync used
     * (a real captured userId, which no longer exists once dispatch moved off the HTTP request thread).
     */
    private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final ProcessingJobRepository processingJobRepository;
    private final DocumentOcrFieldResultRepository documentOcrFieldResultRepository;
    private final DocumentMetadataService documentMetadataService;

    @RabbitListener(queues = RabbitMqConfig.QUEUE_OCR_EVENTS)
    public void onMessage(Map<String, Object> payload) {
        String eventType = (String) payload.get("event_type");
        UUID tenantId = UUID.fromString((String) payload.get("tenant_id"));
        UUID documentId = UUID.fromString((String) payload.get("document_id"));
        UUID versionId = UUID.fromString((String) payload.get("version_id"));

        if (isStale(versionId, payload.get("attempt"))) {
            log.info("Skipping stale OCR event {} for version {}: a newer attempt has already superseded it", eventType, versionId);
            return;
        }

        TenantContext.set(tenantId, SYSTEM_USER_ID);
        try {
            if (RabbitMqConfig.ROUTING_KEY_OCR_COMPLETED.equals(eventType)) {
                applyFieldResults(documentId, tenantId);
            } else if (RabbitMqConfig.ROUTING_KEY_OCR_FAILED.equals(eventType)) {
                log.warn("OCR failed for version {}: {}", versionId, payload.get("error"));
            } else {
                log.warn("Discarding OCR event with unrecognized event_type={} for version {}", eventType, versionId);
            }
        } finally {
            TenantContext.clear();
        }
    }

    /** No job row at all, or no `attempt` on the payload, never counts as stale -- only an explicit, comparable staleness signal skips processing. */
    private boolean isStale(UUID versionId, Object attemptRaw) {
        if (!(attemptRaw instanceof Number attemptNumber)) {
            return false;
        }
        int eventAttempt = attemptNumber.intValue();
        return processingJobRepository
                .findTopByReferenceTypeAndReferenceIdAndJobTypeOrderByCreatedAtDesc(
                        ProcessingJob.REFERENCE_TYPE_DOCUMENT_VERSION, versionId, ProcessingJob.JOB_TYPE_OCR)
                .map(job -> eventAttempt < job.getAttempts())
                .orElse(false);
    }

    /** Mirrors DocumentServiceImpl#getOcrStatus's own poll-fallback mapping
     * loop exactly (document_ocr_field_results row -> the {value, confidence}
     * map shape applyOcrFields expects) -- deliberately not extracted into a
     * shared helper across the document/ocrworker package boundary for a
     * ~10-line loop this stable; kept symmetric on purpose, not accidentally
     * duplicated.
     */
    private void applyFieldResults(UUID documentId, UUID tenantId) {
        List<DocumentOcrFieldResult> rows = documentOcrFieldResultRepository.findByDocumentIdAndTenantId(documentId, tenantId);

        Map<String, Object> ocrFieldsRaw = new LinkedHashMap<>();
        for (DocumentOcrFieldResult row : rows) {
            if (row.getValue() == null || "not_found".equals(row.getStatus())) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("value", row.getValue());
            if (row.getConfidence() != null) {
                entry.put("confidence", row.getConfidence());
            }
            ocrFieldsRaw.put(row.getFieldKey(), entry);
        }

        if (!ocrFieldsRaw.isEmpty()) {
            documentMetadataService.applyOcrFields(documentId, ocrFieldsRaw);
        }
    }
}
