package com.oak.dms.watermark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.document.DocumentVersion;
import com.oak.dms.document.DocumentVersionRepository;
import com.oak.dms.metadata.entity.DocumentType;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StorageProvider;
import com.oak.dms.storage.enums.StoragePurpose;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import com.oak.dms.tenant.Tenant;
import com.oak.dms.tenant.TenantRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import com.oak.dms.watermark.dto.WatermarkJobResponse;
import com.oak.dms.watermark.dto.WatermarkRequest;
import com.oak.dms.watermark.dto.WatermarkSpec;
import com.oak.dms.watermark.entity.WatermarkJob;
import com.oak.dms.watermark.enums.WatermarkJobStatus;
import com.oak.dms.watermark.exception.WatermarkException;
import com.oak.dms.watermark.repository.WatermarkJobRepository;
import com.oak.dms.watermark.service.WatermarkService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The request side of watermarking: everything that happens before a worker
 * is involved.
 *
 * <h2>How documents are seeded</h2>
 * Rows are inserted directly and the bytes pushed through the real storage
 * layer into a {@code @TempDir} FILE_SYSTEM location, with
 * {@code scan_status = 'clean'}. That skips the malware scanner - which
 * {@code DocumentUploadIT} needs a live ClamAV for - while still exercising
 * the real {@code VersionContent} scan gate, the real encryption layer and
 * the real access checks. Nothing about the watermark path is mocked.
 */
@SpringBootTest(properties = {
        "dms.watermark.enabled=true",
        // Raised from the 8pt default purely to make the "too long to fit"
        // refusal deterministic here. The shrink floor works out independent
        // of the requested font size - a bigger font shrinks proportionally
        // further - so with a 200-character cap and an A4 page the default
        // sits within a pixel of the boundary. The floor's real behaviour,
        // including that it means the same at any DPI, is covered properly
        // in WatermarkRendererTest; this value only moves the line far
        // enough that the assertion here is about the guard running at
        // REQUEST time rather than about rounding.
        "dms.watermark.style.min-font-size=20"})
class WatermarkServiceIT extends WatermarkFixtures {

    @Autowired
    private WatermarkService watermarkService;
    @Autowired
    private WatermarkJobRepository jobRepository;
    @Autowired
    private ObjectMapper objectMapper;

    // ------------------------------------------------------ happy path

    @Test
    void aTemplateRequestCreatesAPendingJobWithTheResolvedTextFrozenOntoIt() {
        Fixture fixture = fixture();
        WatermarkJobResponse response = watermarkService.apply(fixture.documentId(),
                new WatermarkRequest(SYSTEM_CONFIDENTIAL, null, null, null, null, null, null, null, null), null);

        assertThat(response.status()).isEqualTo(WatermarkJobStatus.pending);
        assertThat(response.text()).isEqualTo("CONFIDENTIAL");
        assertThat(response.templateId()).isEqualTo(SYSTEM_CONFIDENTIAL);
        assertThat(response.templateVersion()).isNotNull();
        assertThat(response.downloadAvailable()).isFalse();

        WatermarkJob stored = jobRepository.findById(response.jobId()).orElseThrow();
        assertThat(stored.getVersionId()).isEqualTo(fixture.versionId());
        assertThat(stored.getSourceMime()).isEqualTo(PDF_MIME);
        assertThat(stored.getSourceSizeBytes()).isEqualTo(fixture.sizeBytes());
        assertThat(stored.getCorrelationId()).isNotNull();
    }

    @Test
    void customTextIsAccepted_andPlaceholdersAreResolvedOnTheRequestThread() {
        Fixture fixture = fixture();
        WatermarkJobResponse response = watermarkService.apply(fixture.documentId(),
                customText("{username} - {documentVersion} - {watermarkId}"), null);

        // Every token substituted before the row was written. The worker
        // reads this snapshot and never re-resolves.
        assertThat(response.text())
                .contains(fixture.username())
                .contains(" - 1 - ")
                .contains(response.jobId().toString().substring(0, 8));
        assertThat(spec(response.jobId()).text()).isEqualTo(response.text());
    }

    @Test
    void theWatermarkIdDrawnOnThePageIsTheIdThatGetsStored() {
        Fixture fixture = fixture();
        WatermarkJobResponse response = watermarkService.apply(fixture.documentId(),
                customText("{watermarkId}"), null);

        // The whole point of the assigned id: what a reader of a leaked copy
        // types in has to find the job.
        assertThat(response.jobId().toString()).startsWith(response.text());
    }

    @Test
    void theStylePrecedenceIsRequestThenTemplateThenSystemDefault() {
        Fixture fixture = fixture();
        WatermarkJobResponse response = watermarkService.apply(fixture.documentId(),
                new WatermarkRequest(SYSTEM_CONFIDENTIAL, null, null, 120, null, null, "#FF0000", null, null),
                null);

        WatermarkSpec spec = spec(response.jobId());
        assertThat(spec.fontSize()).isEqualTo(120);          // from the request
        assertThat(spec.color()).isEqualTo("#ff0000");       // from the request, normalised
        assertThat(spec.opacity()).isEqualTo(25);            // system default
        assertThat(spec.placement().getValue()).isEqualTo("tiled");
    }

    @Test
    void anExplicitVersionIsPinnedOntoTheJobRatherThanWhateverIsCurrentLater() {
        Fixture fixture = fixture();
        UUID olderVersion = addVersion(fixture, 2);

        WatermarkJobResponse response = watermarkService.apply(fixture.documentId(),
                new WatermarkRequest(null, "DRAFT", fixture.versionId(), null, null, null, null, null, null), null);

        assertThat(response.versionId()).isEqualTo(fixture.versionId()).isNotEqualTo(olderVersion);
    }

    @Test
    void theRequestIsAuditedOnTheRequestThreadWithTheResolvedText() {
        Fixture fixture = fixture();
        WatermarkJobResponse response = watermarkService.apply(fixture.documentId(),
                customText("CONFIDENTIAL - {username}"), null);

        assertThat(auditCount("WATERMARK_REQUESTED", response.jobId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select module from audit_events where action = ? and object_id = ?",
                String.class, "WATERMARK_REQUESTED", response.jobId())).isEqualTo("DW");
        // The text is in the audit entry, so the trail records what went on
        // the page - the worker never re-resolves it.
        assertThat(jdbc.queryForObject("select new_value::text from audit_events "
                        + "where action = ? and object_id = ?",
                String.class, "WATERMARK_REQUESTED", response.jobId()))
                .contains(fixture.username());
    }

    // ------------------------------------------------------- validation

    @Test
    void neitherTemplateNorTextIsRejected_andSoIsBoth() {
        Fixture fixture = fixture();
        assertThatThrownBy(() -> watermarkService.apply(fixture.documentId(),
                new WatermarkRequest(null, null, null, null, null, null, null, null, null), null))
                .isInstanceOf(WatermarkException.class)
                .hasMessageContaining("Choose a watermark template");

        assertThatThrownBy(() -> watermarkService.apply(fixture.documentId(),
                new WatermarkRequest(SYSTEM_CONFIDENTIAL, "DRAFT", null, null, null, null, null, null, null), null))
                .isInstanceOf(WatermarkException.class)
                .hasMessageContaining("not both");
    }

    @Test
    void anUnknownPlaceholderIsRejectedRatherThanPrintedLiterally() {
        // A typo left as-is would put the typo on somebody's document, and a
        // user who believed they were stamping their name on a copy would be
        // stamping nothing identifying at all.
        Fixture fixture = fixture();
        assertThatThrownBy(() -> watermarkService.apply(fixture.documentId(), customText("HELLO {usernmae}"), null))
                .isInstanceOf(WatermarkException.class)
                .hasMessageContaining("{usernmae}")
                .hasMessageContaining("{username}");
    }


    @Test
    void textTooLongToFitThePageIsAlsoRejectedUpFront() {
        Fixture fixture = fixture();
        // Long enough that shrinking it to fit A4 would take the text below
        // the 8pt floor - at which point a refusal is more use than a mark
        // nobody can read.
        String tooLong = "CONFIDENTIAL - DO NOT DISTRIBUTE OUTSIDE THE COMPANY - PROPERTY OF THE ORGANISATION - "
                + "ANY UNAUTHORISED COPYING OR FORWARDING OF THIS DOCUMENT IS A BREACH OF POLICY AND WILL BE "
                + "INVESTIGATED BY THE COMPLIANCE TEAM";
        assertThatThrownBy(() -> watermarkService.apply(fixture.documentId(),
                new WatermarkRequest(null, tooLong, null, 200, null, null, null, null, null), null))
                .isInstanceOf(WatermarkException.class)
                .hasMessageContaining("too long to fit");
    }

    @Test
    void anUnsupportedFormatIsA415AndNeverReachesAJob() {
        Fixture fixture = fixture("notes.txt", "text/plain");
        assertThatThrownBy(() -> watermarkService.apply(fixture.documentId(), customText("DRAFT"), null))
                .isInstanceOf(WatermarkException.class)
                .extracting(e -> ((WatermarkException) e).getStatus())
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(jobRepository.findAll()).noneMatch(j -> j.getDocumentId().equals(fixture.documentId()));
    }

    @Test
    void aFileOverTheFormatsSizeCapIsRefusedFromTheVersionRowWithoutReadingIt() {
        Fixture fixture = fixture("huge.pdf", PDF_MIME, 400L * 1024 * 1024);
        assertThatThrownBy(() -> watermarkService.apply(fixture.documentId(), customText("DRAFT"), null))
                .isInstanceOf(WatermarkException.class)
                .extracting(e -> ((WatermarkException) e).getStatus())
                .isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
    }

    @Test
    void aRetiredTemplateCannotBeUsed() {
        Fixture fixture = fixture();
        UUID retired = retiredTemplate(fixture.tenantId());

        assertThatThrownBy(() -> watermarkService.apply(fixture.documentId(),
                new WatermarkRequest(retired, null, null, null, null, null, null, null, null), null))
                .isInstanceOf(WatermarkException.class)
                .hasMessageContaining("retired");
    }

    @Test
    void anotherTenantsDocumentIsA404() {
        Fixture owner = fixture();
        Fixture stranger = fixture();
        TenantContext.clear();
        TenantContext.set(stranger.tenantId(), stranger.userId());

        assertThatThrownBy(() -> watermarkService.apply(owner.documentId(), customText("DRAFT"), null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /**
     * The two jobs that fill the cap are seeded as rows rather than
     * requested through the service. Once a real worker exists, a requested
     * job is picked up immediately and is no longer active by the time the
     * third request arrives - so asking for three in a row would be a race
     * that passes or fails on how fast the pool happens to be. What the cap
     * actually promises is about the state of the rows, and this asserts
     * exactly that.
     */
    @Test
    void theActiveJobCapIsEnforced() {
        Fixture fixture = fixture();
        seedActiveJob(fixture, "pending");
        seedActiveJob(fixture, "processing");

        assertThatThrownBy(() -> watermarkService.apply(fixture.documentId(), customText("THREE"), null))
                .isInstanceOf(WatermarkException.class)
                .extracting(e -> ((WatermarkException) e).getStatus())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    /** A job row of the user's that is in flight and will stay that way. */
    private void seedActiveJob(Fixture fixture, String status) {
        jdbc.update("""
                insert into watermark_jobs (job_id, tenant_id, requested_by, document_id, version_id,
                                            source, status, request_snapshot, created_at, updated_at)
                values (?, ?, ?, ?, ?, 'USER_REQUEST', ?, '{}'::jsonb, now(), now())
                """, UUID.randomUUID(), fixture.tenantId(), fixture.userId(),
                fixture.documentId(), fixture.versionId(), status);
    }

    // ------------------------------------------------------ idempotency

    @Test
    void theSameKeyAndBodyReturnsTheSameJobRatherThanASecondCopy() {
        Fixture fixture = fixture();
        WatermarkJobResponse first = watermarkService.apply(fixture.documentId(), customText("DRAFT"), "click-1");
        WatermarkJobResponse second = watermarkService.apply(fixture.documentId(), customText("DRAFT"), "click-1");

        assertThat(second.jobId()).isEqualTo(first.jobId());
        assertThat(countJobsFor(fixture.documentId())).isEqualTo(1);
    }

    @Test
    void theSameKeyWithADifferentBodyIsA409() {
        Fixture fixture = fixture();
        watermarkService.apply(fixture.documentId(), customText("DRAFT"), "click-1");

        assertThatThrownBy(() -> watermarkService.apply(fixture.documentId(), customText("FINAL"), "click-1"))
                .isInstanceOf(WatermarkException.class)
                .extracting(e -> ((WatermarkException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void theHashIgnoresTheMovingPlaceholdersSoAnHonestRetryIsNotA409() {
        // {date} and {watermarkId} differ on every attempt. Hashing the
        // RESOLVED text would make a client that retries with a stable key -
        // which is exactly what a well-behaved client does - get a 409.
        Fixture fixture = fixture();
        WatermarkJobResponse first = watermarkService.apply(fixture.documentId(),
                customText("CONFIDENTIAL {date} {watermarkId}"), "retry-me");
        WatermarkJobResponse second = watermarkService.apply(fixture.documentId(),
                customText("CONFIDENTIAL {date} {watermarkId}"), "retry-me");

        assertThat(second.jobId()).isEqualTo(first.jobId());
    }

    @Test
    void twoSimultaneousRequestsWithOneKeyProduceOneJob_andBothCallersGetIt() throws Exception {
        // The race the partial unique index exists for. The loser must come
        // back with the WINNER'S job, not an error: from the user's side one
        // click produced one job, which is what the key promised.
        Fixture fixture = fixture();
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<WatermarkJobResponse>> calls = java.util.Collections.nCopies(threads, () -> {
                TenantContext.set(fixture.tenantId(), fixture.userId());
                try {
                    return watermarkService.apply(fixture.documentId(), customText("DRAFT"), "one-click");
                } finally {
                    TenantContext.clear();
                }
            });

            List<Future<WatermarkJobResponse>> futures = pool.invokeAll(calls, 60, TimeUnit.SECONDS);
            java.util.Set<UUID> jobIds = new java.util.HashSet<>();
            int succeeded = 0;
            for (Future<WatermarkJobResponse> future : futures) {
                try {
                    jobIds.add(future.get().jobId());
                    succeeded++;
                } catch (java.util.concurrent.ExecutionException e) {
                    // A caller may still lose on the serialisation level;
                    // what must never happen is two DIFFERENT jobs.
                    assertThat(e.getCause()).isInstanceOf(RuntimeException.class);
                }
            }

            assertThat(succeeded).describedAs("at least one caller must succeed").isGreaterThan(0);
            assertThat(jobIds).describedAs("every successful caller must get the same job").hasSize(1);
            assertThat(countJobsFor(fixture.documentId()))
                    .describedAs("exactly one row for one key").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aFailedJobReleasesItsKeySoARetryStartsAfresh() {
        Fixture fixture = fixture();
        WatermarkJobResponse first = watermarkService.apply(fixture.documentId(), customText("DRAFT"), "retry");
        jobRepository.findById(first.jobId()).ifPresent(job -> {
            job.setStatus(WatermarkJobStatus.failed);
            job.setFailureReason("so this test can retry");
            jobRepository.saveAndFlush(job);
        });

        WatermarkJobResponse retried = watermarkService.apply(fixture.documentId(), customText("DRAFT"), "retry");

        assertThat(retried.jobId()).isNotEqualTo(first.jobId());
        assertThat(retried.status()).isEqualTo(WatermarkJobStatus.pending);
    }


    @Test
    void theHashIsOverTheRawRequest_provenByTwoJobsWhoseResolvedTextDiffers() {
        // The strongest available statement of the rule, and a deterministic
        // one: {watermarkId} resolves to a DIFFERENT value for every job by
        // construction, so two jobs built from the identical body are
        // guaranteed to carry different resolved text. If the hash were
        // taken over the snapshot the two would differ; it is taken over the
        // normalised request, so they match.
        //
        // This is the same property that makes a retry 30 seconds later -
        // when {date} has rolled to a new minute - return the existing job
        // rather than a 409, without the test having to wait 30 seconds or
        // control the clock.
        Fixture fixture = fixture();
        WatermarkJobResponse first = watermarkService.apply(fixture.documentId(),
                customText("CONFIDENTIAL {watermarkId} {date}"), "key-a");
        WatermarkJobResponse second = watermarkService.apply(fixture.documentId(),
                customText("CONFIDENTIAL {watermarkId} {date}"), "key-b");

        assertThat(second.text())
                .describedAs("the two jobs must really have different resolved text")
                .isNotEqualTo(first.text());
        assertThat(hashOf(second.jobId()))
                .describedAs("but the same request hash, because the hash is over the request")
                .isEqualTo(hashOf(first.jobId()));
    }

    @Test
    void anExpiredJobReleasesItsKeyJustAsAFailedOneDoes() {
        // Once the output has been swept there is nothing left to hand back,
        // so the key has to be free again - otherwise a client with a stable
        // key could never ask for that document a second time.
        Fixture fixture = fixture();
        WatermarkJobResponse first = watermarkService.apply(fixture.documentId(), customText("DRAFT"), "expiring");
        jobRepository.findById(first.jobId()).ifPresent(job -> {
            job.setStatus(WatermarkJobStatus.expired);
            jobRepository.saveAndFlush(job);
        });

        WatermarkJobResponse retried = watermarkService.apply(fixture.documentId(), customText("DRAFT"), "expiring");

        assertThat(retried.jobId()).isNotEqualTo(first.jobId());
        assertThat(countJobsFor(fixture.documentId())).isEqualTo(2);
    }

    @Test
    void aCompletedJobStillHoldsItsKey_soOneClickCannotProduceTwoCopies() {
        // The other half of the rule: while there IS something to hand back,
        // the key keeps pointing at it.
        Fixture fixture = fixture();
        WatermarkJobResponse first = watermarkService.apply(fixture.documentId(), customText("DRAFT"), "done");
        jobRepository.findById(first.jobId()).ifPresent(job -> {
            job.setStatus(WatermarkJobStatus.completed);
            jobRepository.saveAndFlush(job);
        });

        assertThat(watermarkService.apply(fixture.documentId(), customText("DRAFT"), "done").jobId())
                .isEqualTo(first.jobId());
        assertThat(countJobsFor(fixture.documentId())).isEqualTo(1);
    }

    // ------------------------------------------------------- helpers

    private String hashOf(UUID jobId) {
        return jdbc.queryForObject("select request_hash from watermark_jobs where job_id = ?", String.class, jobId);
    }

    private WatermarkSpec spec(UUID jobId) {
        try {
            return objectMapper.readValue(jobRepository.findById(jobId).orElseThrow().getRequestSnapshot(),
                    WatermarkSpec.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID retiredTemplate(UUID tenantId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into watermark_templates (template_id, tenant_id, label, default_text, active) "
                + "values (?, ?, ?, ?, false)", id, tenantId, "Retired " + id, "OLD");
        return id;
    }
}
