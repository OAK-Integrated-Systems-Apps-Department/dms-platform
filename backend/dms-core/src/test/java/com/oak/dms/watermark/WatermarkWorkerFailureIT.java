package com.oak.dms.watermark;

import com.oak.dms.common.exception.KeycloakIntegrationException;
import com.oak.dms.security.keycloak.KeycloakUserService;
import com.oak.dms.watermark.dto.WatermarkJobResponse;
import com.oak.dms.watermark.entity.WatermarkJob;
import com.oak.dms.watermark.enums.WatermarkJobStatus;
import com.oak.dms.watermark.format.WatermarkFormatService;
import com.oak.dms.watermark.format.WatermarkOptions;
import com.oak.dms.watermark.format.WatermarkServiceFactory;
import com.oak.dms.watermark.job.WatermarkFailure;
import com.oak.dms.watermark.job.WatermarkJobWorker;
import com.oak.dms.watermark.job.WatermarkMetrics;
import com.oak.dms.watermark.repository.WatermarkJobRepository;
import com.oak.dms.watermark.service.WatermarkService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the worker does when the thing it is waiting on never comes back.
 *
 * <h2>Why this is a separate test from the pipeline one</h2>
 * Every case here needs a format service that misbehaves on purpose - one
 * that hangs and ignores interruption the way PDFBox and POI do - so the
 * real {@link WatermarkServiceFactory} is replaced. That makes this test
 * useless for proving the happy path, which is exactly what
 * {@code WatermarkPipelineIT} is for, with nothing mocked but Keycloak.
 *
 * <h2>The property overrides</h2>
 * A two-second format timeout and a format pool of two, so a hang is
 * observable inside a test's patience and the pool can actually be filled.
 * {@code stuck-after} is one second so the resume rule can be tested without
 * waiting fifteen minutes for a row to look old.
 */
@SpringBootTest(properties = {
        "dms.watermark.enabled=true",
        "dms.watermark.format-timeout=PT2S",
        "dms.watermark.stuck-after=PT1S",
        "dms.watermark.executor.job-core-size=4",
        "dms.watermark.executor.job-max-size=4",
        "dms.watermark.executor.format-max-size=2",
})
class WatermarkWorkerFailureIT extends WatermarkFixtures {

    private static final Duration PATIENCE = Duration.ofSeconds(30);

    @Autowired
    private WatermarkService watermarkService;
    @Autowired
    private WatermarkJobRepository jobRepository;
    @Autowired
    private WatermarkJobWorker worker;
    @Autowired
    private WatermarkMetrics metrics;
    @Autowired
    private MeterRegistry registry;

    /** The worker runs as the requester, whose realm roles come from Keycloak. */
    @MockitoBean
    private KeycloakUserService keycloakUserService;

    /** Replaced so the format service can be made to hang on demand. */
    @MockitoBean
    private WatermarkServiceFactory formatServices;

    /**
     * Counts down when a hung call has actually started, so a test waits for
     * the thread to be occupied rather than guessing at a sleep.
     */
    private CountDownLatch entered;
    /**
     * Released in {@link #releaseEveryHungThread()}. The worker cannot stop
     * these threads - that is the whole point - so the test has to, or the
     * JVM would carry them to the end of the run.
     */
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void stubCollaborators() {
        Mockito.when(keycloakUserService.getPlatformRealmRoles(ArgumentMatchers.any()))
                .thenReturn(List.of());
        Mockito.when(formatServices.isSupported(ArgumentMatchers.any())).thenReturn(true);
        Mockito.when(formatServices.supportedFormats()).thenReturn("PDF");
        Mockito.when(formatServices.forMimeType(ArgumentMatchers.any())).thenReturn(hangingService());
        entered = new CountDownLatch(1);
    }

    @AfterEach
    void releaseEveryHungThread() {
        release.countDown();
    }

    /**
     * A format service that never returns and never notices an interrupt -
     * the behaviour {@code Future.cancel(true)} cannot do anything about,
     * and the reason the format pool exists at all.
     */
    private WatermarkFormatService hangingService() {
        return new WatermarkFormatService() {
            @Override
            public Set<String> mimeTypes() {
                return Set.of(PDF_MIME);
            }

            @Override
            public byte[] watermark(InputStream input, WatermarkOptions options) {
                entered.countDown();
                while (release.getCount() > 0) {
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        // Swallowed deliberately: PDFBox and POI do not check
                        // the interrupt flag either. Restoring it here would
                        // make this test prove something nicer than the truth.
                    }
                }
                return new byte[0];
            }
        };
    }

    @Test
    void aHungFormatServiceTimesOutAndItsThreadIsCountedAsAbandoned() {
        int abandonedBefore = metrics.abandonedThreads();
        Fixture fixture = fixture();

        UUID jobId = watermarkService.apply(fixture.documentId(), customText("CONFIDENTIAL"), null).jobId();
        WatermarkJob job = awaitFinish(jobId);

        assertThat(job.getStatus()).isEqualTo(WatermarkJobStatus.failed);
        assertThat(job.getFailureCode()).isEqualTo(WatermarkFailure.TIMED_OUT.code());
        // A timeout is the document's fault, not the server's: retrying it
        // would hang exactly as long again.
        assertThat(WatermarkFailure.TIMED_OUT.retryable()).isFalse();
        assertThat(metrics.abandonedThreads())
                .describedAs("the thread could not be interrupted, so it must be counted")
                .isEqualTo(abandonedBefore + 1);
        // The job thread came back - that is what the second pool buys.
        assertThat(job.getCompletedAt()).isNotNull();
        assertThat(job.getOutputObjectKey())
                .describedAs("a failed job must not leave a half-written file behind")
                .isNull();
    }

    @Test
    void whenEveryFormatThreadIsAbandonedTheNextJobFailsRetryablyRatherThanWaiting() throws Exception {
        // Fill both format threads. Separate fixtures because the active-job
        // cap is per user, not per instance.
        UUID firstHung = startAndWaitUntilDrawing(fixture());
        UUID secondHung = startAndWaitUntilDrawing(fixture());

        // Both are now abandoned; the pool has a SynchronousQueue, so the
        // third submit is rejected outright instead of queueing behind work
        // that will never finish.
        UUID rejected = watermarkService.apply(fixture().documentId(), customText("CONFIDENTIAL"), null).jobId();
        WatermarkJob job = awaitFinish(rejected);

        assertThat(job.getStatus()).isEqualTo(WatermarkJobStatus.failed);
        assertThat(job.getFailureCode()).isEqualTo(WatermarkFailure.FORMAT_CAPACITY.code());
        // Unlike a timeout, this one says nothing about the document - it is
        // the server being out of room, so it is worth trying again.
        assertThat(WatermarkFailure.FORMAT_CAPACITY.retryable()).isTrue();
        assertThat(metrics.abandonedThreads()).isGreaterThanOrEqualTo(2);
        assertThat(List.of(firstHung, secondHung)).doesNotContain(rejected);
    }

    @Test
    void aKeycloakOutageFailsTheJobRetryablyInsteadOfClaimingAccessWasLost() {
        Fixture fixture = fixture();
        // The request side is already past; this is the worker re-checking
        // the requester's realm roles as a background job.
        Mockito.when(keycloakUserService.getPlatformRealmRoles(ArgumentMatchers.any()))
                .thenThrow(new KeycloakIntegrationException("Keycloak is down"));

        UUID jobId = watermarkService.apply(fixture.documentId(), customText("CONFIDENTIAL"), null).jobId();
        WatermarkJob job = awaitFinish(jobId);

        assertThat(job.getStatus()).isEqualTo(WatermarkJobStatus.failed);
        assertThat(job.getFailureCode())
                .describedAs("an outage must not be reported to the user as lost access")
                .isEqualTo(WatermarkFailure.IDENTITY_UNAVAILABLE.code())
                .isNotEqualTo(WatermarkFailure.ACCESS_LOST.code());
        assertThat(WatermarkFailure.IDENTITY_UNAVAILABLE.retryable()).isTrue();
    }

    @Test
    void aJobLeftProcessingByTheLastInstanceIsPickedUpAgain() {
        Fixture fixture = fixture();
        UUID jobId = watermarkService.apply(fixture.documentId(), customText("CONFIDENTIAL"), null).jobId();
        awaitFinish(jobId);

        // Put it back the way a killed process would have left it: claimed,
        // started a moment ago, with no thread behind it.
        jdbc.update("update watermark_jobs set status = 'processing', started_at = now(), "
                + "completed_at = null, failure_code = null, failure_reason = null where job_id = ?", jobId);

        worker.resumeInterrupted();

        WatermarkJob resumed = awaitFinish(jobId);
        assertThat(resumed.getStatus())
                .describedAs("a young processing row is queued again, not written off")
                .isEqualTo(WatermarkJobStatus.failed);
        assertThat(resumed.getFailureCode())
                .describedAs("it ran again and hit the same hung service, which is proof it ran")
                .isEqualTo(WatermarkFailure.TIMED_OUT.code());
    }

    @Test
    void aJobStillProcessingAfterStuckAfterIsFailedRatherThanRetriedForever() {
        Fixture fixture = fixture();
        UUID jobId = watermarkService.apply(fixture.documentId(), customText("CONFIDENTIAL"), null).jobId();
        awaitFinish(jobId);

        // Older than stuck-after: most likely the file itself killed the
        // last process, and resuming it on every restart would be a loop.
        jdbc.update("update watermark_jobs set status = 'processing', started_at = now() - interval '1 hour', "
                + "completed_at = null, failure_code = null, failure_reason = null where job_id = ?", jobId);

        worker.resumeInterrupted();

        WatermarkJob job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(WatermarkJobStatus.failed);
        assertThat(job.getFailureCode()).isEqualTo(WatermarkFailure.UNEXPECTED.code());
        assertThat(job.getCompletedAt())
                .describedAs("failed immediately, without being queued")
                .isNotNull();
    }

    /**
     * Tag cardinality, which is the thing about metrics that goes wrong
     * quietly. A tenant, user or document id on any of these would mint a
     * new time series per tenant, per user or per document, and the damage
     * shows up as a Prometheus bill or a dead scrape long after the commit
     * that caused it. Format, outcome and reason are all closed sets.
     */
    @Test
    void noMetricCarriesATagThatCouldGrowWithoutLimit() {
        Fixture fixture = fixture();
        awaitFinish(watermarkService.apply(fixture.documentId(), customText("CONFIDENTIAL"), null).jobId());

        assertThat(tagsOf("watermark.jobs.started")).containsExactly("format");
        assertThat(tagsOf("watermark.job.duration")).containsExactlyInAnyOrder("format", "outcome");
        assertThat(tagsOf("watermark.jobs.failed")).containsExactlyInAnyOrder("format", "reason");
        assertThat(tagsOf("watermark.format.threads.abandoned")).isEmpty();

        // And the values of the open-looking one are a closed set too.
        assertThat(valuesOf("watermark.jobs.failed", "reason"))
                .allMatch(reason -> Arrays.stream(WatermarkFailure.values())
                        .anyMatch(failure -> failure.code().equals(reason)));
    }

    /**
     * Every series carries these two (management.metrics.tags in application.yml): one fixed value
     * each per deployment, so they cannot grow. The guard above is about the tags a meter adds.
     */
    private static final Set<String> DEPLOYMENT_TAGS = Set.of("application", "environment");

    private Set<String> tagsOf(String meterName) {
        return registry.find(meterName).meters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(Tag::getKey)
                .filter(key -> !DEPLOYMENT_TAGS.contains(key))
                .collect(Collectors.toSet());
    }

    private Set<String> valuesOf(String meterName, String tag) {
        return registry.find(meterName).meters().stream()
                .map(meter -> meter.getId().getTag(tag))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    // ---------------------------------------------------------- helpers

    /** Starts a job and returns once its format call is actually running. */
    private UUID startAndWaitUntilDrawing(Fixture fixture) throws InterruptedException {
        entered = new CountDownLatch(1);
        WatermarkJobResponse response = watermarkService.apply(fixture.documentId(),
                customText("CONFIDENTIAL"), null);
        assertThat(entered.await(PATIENCE.toSeconds(), TimeUnit.SECONDS))
                .describedAs("the format service should have been reached")
                .isTrue();
        return response.jobId();
    }

    private WatermarkJob awaitFinish(UUID jobId) {
        Instant deadline = Instant.now().plus(PATIENCE);
        while (Instant.now().isBefore(deadline)) {
            WatermarkJob job = jobRepository.findById(jobId).orElseThrow();
            if (job.getStatus() == WatermarkJobStatus.completed
                    || job.getStatus() == WatermarkJobStatus.failed) {
                return job;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("Watermark job " + jobId + " did not finish within " + PATIENCE
                + " (status " + jobRepository.findById(jobId).map(WatermarkJob::getStatus).orElse(null) + ")");
    }

}
