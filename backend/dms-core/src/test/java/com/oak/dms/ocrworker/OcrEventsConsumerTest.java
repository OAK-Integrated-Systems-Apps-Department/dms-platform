package com.oak.dms.ocrworker;

import com.oak.dms.document.DocumentMetadataService;
import com.oak.dms.document.DocumentOcrFieldResult;
import com.oak.dms.document.DocumentOcrFieldResultRepository;
import com.oak.dms.document.DocumentVersionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OcrEventsConsumer}: the staleness check and the
 * .completed/.failed dispatch, with the repository/service layer mocked --
 * a real end-to-end run against the live dev stack (publishing a real
 * event) is the companion verification for this, matching how B.1's
 * DocumentUploadOcrQueueingIT complements this class's own unit coverage.
 */
@ExtendWith(MockitoExtension.class)
class OcrEventsConsumerTest {

    @Mock
    private ProcessingJobRepository processingJobRepository;
    @Mock
    private DocumentOcrFieldResultRepository documentOcrFieldResultRepository;
    @Mock
    private DocumentMetadataService documentMetadataService;
    @Mock
    private DocumentVersionRepository documentVersionRepository;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();

    private OcrEventsConsumer consumer() {
        return new OcrEventsConsumer(processingJobRepository, documentOcrFieldResultRepository, documentMetadataService, documentVersionRepository);
    }

    private Map<String, Object> completedPayload(int attempt) {
        return Map.of(
                "event_type", "document.version.ocr.completed",
                "tenant_id", tenantId.toString(),
                "document_id", documentId.toString(),
                "version_id", versionId.toString(),
                "attempt", attempt);
    }

    private Map<String, Object> failedPayload(int attempt) {
        return Map.of(
                "event_type", "document.version.ocr.failed",
                "tenant_id", tenantId.toString(),
                "document_id", documentId.toString(),
                "version_id", versionId.toString(),
                "error", "TIMEOUT: took too long",
                "attempt", attempt);
    }

    private DocumentOcrFieldResult row(String fieldKey, String value, String status, Double confidence) {
        return DocumentOcrFieldResult.builder()
                .fieldResultId(UUID.randomUUID())
                .tenantId(tenantId)
                .documentId(documentId)
                .versionId(versionId)
                .fieldKey(fieldKey)
                .value(value)
                .status(status)
                .confidence(confidence != null ? BigDecimal.valueOf(confidence) : null)
                .build();
    }

    private ProcessingJob jobWithAttempts(int attempts) {
        return ProcessingJob.builder()
                .jobId(UUID.randomUUID())
                .tenantId(tenantId)
                .jobType(ProcessingJob.JOB_TYPE_OCR)
                .referenceType(ProcessingJob.REFERENCE_TYPE_DOCUMENT_VERSION)
                .referenceId(versionId)
                .status(ProcessingJob.STATUS_QUEUED)
                .attempts(attempts)
                .build();
    }

    @Test
    void completedEventAppliesExtractedFieldsOntoDocumentMetadata() {
        when(processingJobRepository.findTopByReferenceTypeAndReferenceIdAndJobTypeOrderByCreatedAtDesc(
                anyString(), any(), anyString())).thenReturn(java.util.Optional.of(jobWithAttempts(1)));
        when(documentOcrFieldResultRepository.findByDocumentIdAndTenantId(documentId, tenantId)).thenReturn(List.of(
                row("invoice_number", "INV-123", "extracted", 0.95),
                row("total_amount", "500.00", "extracted", 0.9),
                row("vendor_name", null, "not_found", null)));

        consumer().onMessage(completedPayload(1));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(documentMetadataService).applyOcrFields(org.mockito.ArgumentMatchers.eq(documentId), captor.capture());

        Map<String, Object> applied = captor.getValue();
        assertThat(applied).containsKey("invoice_number");
        assertThat(applied).containsKey("total_amount");
        // not_found rows are never applied -- there's nothing to write for a field OCR couldn't find.
        assertThat(applied).doesNotContainKey("vendor_name");
    }

    @Test
    void failedEventNeverCallsApplyOcrFields() {
        when(processingJobRepository.findTopByReferenceTypeAndReferenceIdAndJobTypeOrderByCreatedAtDesc(
                anyString(), any(), anyString())).thenReturn(java.util.Optional.of(jobWithAttempts(1)));

        consumer().onMessage(failedPayload(1));

        verify(documentMetadataService, never()).applyOcrFields(any(), any());
        // ocr-worker already wrote ocr_status/last_error directly -- nothing else for this consumer to do.
        verify(documentOcrFieldResultRepository, never()).findByDocumentIdAndTenantId(any(), any());
    }

    @Test
    void staleEventIsSkippedWhenANewerAttemptHasAlreadySupersededIt() {
        when(processingJobRepository.findTopByReferenceTypeAndReferenceIdAndJobTypeOrderByCreatedAtDesc(
                anyString(), any(), anyString())).thenReturn(java.util.Optional.of(jobWithAttempts(3)));

        // Event carries attempt=1, but the reaper has already moved this
        // job on to attempt 3 -- a fresh retry has superseded this event.
        consumer().onMessage(completedPayload(1));

        verify(documentMetadataService, never()).applyOcrFields(any(), any());
        verify(documentOcrFieldResultRepository, never()).findByDocumentIdAndTenantId(any(), any());
    }

    @Test
    void aFreshEventMatchingTheCurrentAttemptIsNotStale() {
        when(processingJobRepository.findTopByReferenceTypeAndReferenceIdAndJobTypeOrderByCreatedAtDesc(
                anyString(), any(), anyString())).thenReturn(java.util.Optional.of(jobWithAttempts(1)));
        when(documentOcrFieldResultRepository.findByDocumentIdAndTenantId(documentId, tenantId)).thenReturn(List.of(
                row("invoice_number", "INV-123", "extracted", 0.95)));

        consumer().onMessage(completedPayload(1));

        verify(documentMetadataService).applyOcrFields(any(), any());
    }

    @Test
    void aMissingJobRowIsNeverTreatedAsStale() {
        // No job row at all (e.g. a race, or test data gap) -- never skip
        // on missing data, only on an explicit, comparable staleness signal.
        when(processingJobRepository.findTopByReferenceTypeAndReferenceIdAndJobTypeOrderByCreatedAtDesc(
                anyString(), any(), anyString())).thenReturn(java.util.Optional.empty());
        when(documentOcrFieldResultRepository.findByDocumentIdAndTenantId(documentId, tenantId)).thenReturn(List.of(
                row("invoice_number", "INV-123", "extracted", 0.95)));

        consumer().onMessage(completedPayload(1));

        verify(documentMetadataService).applyOcrFields(any(), any());
    }

    @Test
    void reprocessingTheSameCompletedEventTwiceCallsApplyOcrFieldsBothTimes() {
        // Idempotency itself lives in applyOcrFields (delete-then-insert
        // per field, verified separately in
        // DocumentMetadataServiceApplyOcrFieldsTest) -- this just proves
        // the consumer doesn't add its own dedupe-skip logic on top that
        // would prevent a legitimate redelivery from being reprocessed.
        when(processingJobRepository.findTopByReferenceTypeAndReferenceIdAndJobTypeOrderByCreatedAtDesc(
                anyString(), any(), anyString())).thenReturn(java.util.Optional.of(jobWithAttempts(1)));
        when(documentOcrFieldResultRepository.findByDocumentIdAndTenantId(documentId, tenantId)).thenReturn(List.of(
                row("invoice_number", "INV-123", "extracted", 0.95)));

        OcrEventsConsumer consumer = consumer();
        consumer.onMessage(completedPayload(1));
        consumer.onMessage(completedPayload(1));

        verify(documentMetadataService, org.mockito.Mockito.times(2)).applyOcrFields(any(), any());
    }
}
