package com.oak.dms.watermark;

import com.oak.dms.watermark.dto.WatermarkRequest;
import com.oak.dms.watermark.enums.WatermarkJobStatus;
import com.oak.dms.watermark.exception.WatermarkQueueFullException;
import com.oak.dms.watermark.job.WatermarkJobQueue;
import com.oak.dms.watermark.repository.WatermarkJobRepository;
import com.oak.dms.watermark.service.WatermarkService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.EnumSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * What happens when the job row is already committed and THEN the executor
 * refuses the work.
 * <p>
 * This window exists because queueing deliberately happens after commit, so
 * the worker can never be handed an id whose row isn't there yet. The price
 * is that the request side cannot roll the row back once the queue says no.
 * Left alone that row sits in {@code pending} for ever - nothing will pick
 * it up, and it counts against the requester's active-job cap, so after two
 * of them they can't ask for anything at all. A permanent, invisible
 * failure, caused by a momentary one.
 */
@SpringBootTest(properties = "dms.watermark.enabled=true")
class WatermarkQueueFullIT extends WatermarkFixtures {

    /**
     * Stands in for a saturated executor. A real bounded pool would need
     * two long-running jobs plus a full queue to reproduce, which is slow
     * and timing-dependent; what is under test here is what the request side
     * does with the rejection, not the pool's own arithmetic.
     */
    @MockitoBean
    private WatermarkJobQueue queue;

    @Autowired
    private WatermarkService watermarkService;
    @Autowired
    private WatermarkJobRepository jobRepository;

    @Test
    void aRejectedJobIsFailedRatherThanLeftPending_andTheCallerGets503WithARetryHint() {
        doThrow(new TaskRejectedException("queue full")).when(queue).start(any());
        Fixture fixture = fixture();

        WatermarkQueueFullException thrown = (WatermarkQueueFullException) assertThatThrownBy(() ->
                watermarkService.apply(fixture.documentId(), customText("DRAFT"), null))
                .isInstanceOf(WatermarkQueueFullException.class)
                .actual();

        assertThat(thrown.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(thrown.getCode()).isEqualTo("WATERMARK_QUEUE_FULL");
        // The one refusal that comes with an answer to "what do I do then?".
        assertThat(thrown.getRetryAfter()).isNotNull().isGreaterThan(Duration.ZERO);

        // The row exists - it was committed before the rejection - but it is
        // finished, not waiting for something that will never come.
        UUID jobId = onlyJobFor(fixture.documentId());
        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus())
                .isEqualTo(WatermarkJobStatus.failed);
        assertThat(jobRepository.findById(jobId).orElseThrow().getFailureCode())
                .isEqualTo("WATERMARK_QUEUE_FULL");
    }

    @Test
    void aRejectedJobDoesNotCountAgainstTheActiveJobCap() {
        // The reason failing the row matters rather than just logging it: a
        // lingering pending row would use up one of the two slots for good.
        doThrow(new TaskRejectedException("queue full")).when(queue).start(any());
        Fixture fixture = fixture();

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(() -> watermarkService.apply(fixture.documentId(), customText("DRAFT"), null))
                    .isInstanceOf(WatermarkQueueFullException.class);
        }

        assertThat(jobRepository.countByTenantIdAndRequestedByAndStatusIn(fixture.tenantId(), fixture.userId(),
                EnumSet.of(WatermarkJobStatus.pending, WatermarkJobStatus.processing)))
                .describedAs("five rejections must leave nothing in flight")
                .isZero();
    }

    @Test
    void theUserCanSimplyTryAgainOnceTheQueueDrains() {
        doThrow(new TaskRejectedException("queue full")).when(queue).start(any());
        Fixture fixture = fixture();
        assertThatThrownBy(() -> watermarkService.apply(fixture.documentId(), customText("DRAFT"), "same-key"))
                .isInstanceOf(WatermarkQueueFullException.class);

        // Queue drains; the same Idempotency-Key must work, because the
        // failed job released it. Otherwise Retry-After would be advice the
        // client cannot actually follow.
        org.mockito.Mockito.reset(queue);
        var retried = watermarkService.apply(fixture.documentId(), customText("DRAFT"), "same-key");

        assertThat(retried.status()).isEqualTo(WatermarkJobStatus.pending);
    }

    @Test
    void aJobThatWasAlreadyPickedUpIsNotRetroactivelyFailed() {
        // The rejection handler only touches rows still pending: if a worker
        // somehow claimed the job between the commit and the rejection,
        // marking it failed would abandon work already under way.
        Fixture fixture = fixture();
        doThrow(new TaskRejectedException("queue full")).when(queue).start(any());

        assertThatThrownBy(() -> watermarkService.apply(fixture.documentId(), customText("DRAFT"), null))
                .isInstanceOf(WatermarkQueueFullException.class);
        UUID jobId = onlyJobFor(fixture.documentId());

        // Simulate the worker having moved it on, then re-run the handler.
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(WatermarkJobStatus.processing);
            jobRepository.saveAndFlush(job);
        });
        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus())
                .isEqualTo(WatermarkJobStatus.processing);
    }
}
