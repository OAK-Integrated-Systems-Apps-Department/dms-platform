package com.oak.dms.ocrworker;

import com.oak.dms.outbox.OutboxEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OcrEnqueueService#enqueue}'s three quiet-decline
 * paths. "OCR disabled" and "job-token secret missing" must both return
 * {@code false} without throwing (the method runs inside the version-
 * promotion transaction - see the class javadoc), but only the latter is a
 * misconfiguration worth being loud about; that loudness is
 * {@link OcrJobTokenHealthIndicator}'s job, covered separately.
 */
@ExtendWith(MockitoExtension.class)
class OcrEnqueueServiceTest {

    @Mock private ProcessingJobRepository processingJobRepository;
    @Mock private OutboxEventPublisher outboxEventPublisher;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();

    private OcrProperties enabledProperties() {
        OcrProperties properties = new OcrProperties();
        properties.setEnabled(true);
        properties.setMaxQueuedPerTenant(10);
        properties.setJobTokenMinutes(30);
        return properties;
    }

    @Test
    void ocrDisabled_declinesWithoutTouchingTheJobTokenCodec() {
        OcrProperties properties = new OcrProperties();
        properties.setEnabled(false);
        OcrEnqueueService service = new OcrEnqueueService(
                processingJobRepository, outboxEventPublisher, properties, new OcrJobTokenCodec(""));

        boolean queued = service.enqueue(tenantId, documentId, versionId, null, OcrEnqueueService.Source.interactive);

        assertThat(queued).isFalse();
        verify(processingJobRepository, never()).save(any());
        verify(outboxEventPublisher, never()).publish(any(), any(), any(), any(), any());
    }

    @Test
    void ocrEnabledButJobTokenSecretMissing_declinesRatherThanThrowing() {
        OcrEnqueueService service = new OcrEnqueueService(
                processingJobRepository, outboxEventPublisher, enabledProperties(), new OcrJobTokenCodec(""));

        boolean queued = service.enqueue(tenantId, documentId, versionId, null, OcrEnqueueService.Source.interactive);

        assertThat(queued).isFalse();
        verify(processingJobRepository, never()).save(any());
        verify(outboxEventPublisher, never()).publish(any(), any(), any(), any(), any());
    }

    @Test
    void ocrEnabledWithSecretConfigured_queuesNormally() {
        when(processingJobRepository.findTopByReferenceTypeAndReferenceIdAndJobTypeOrderByCreatedAtDesc(
                any(), any(), any())).thenReturn(java.util.Optional.empty());
        when(processingJobRepository.countActiveOcrJobsForTenant(any())).thenReturn(0L);
        OcrEnqueueService service = new OcrEnqueueService(
                processingJobRepository, outboxEventPublisher, enabledProperties(),
                new OcrJobTokenCodec("a-real-secret"));

        boolean queued = service.enqueue(tenantId, documentId, versionId, null, OcrEnqueueService.Source.interactive);

        assertThat(queued).isTrue();
        verify(processingJobRepository).save(any());
        verify(outboxEventPublisher).publish(any(), any(), any(), any(), any());
    }
}
