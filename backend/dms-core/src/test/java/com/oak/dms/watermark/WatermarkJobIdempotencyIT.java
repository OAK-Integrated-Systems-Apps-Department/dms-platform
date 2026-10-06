package com.oak.dms.watermark;

import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.tenant.Tenant;
import com.oak.dms.tenant.TenantRepository;
import com.oak.dms.watermark.entity.WatermarkJob;
import com.oak.dms.watermark.enums.WatermarkJobStatus;
import com.oak.dms.watermark.repository.WatermarkJobRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What an Idempotency-Key means, and - just as importantly - when it stops
 * meaning it.
 * <p>
 * A key is a promise that a double-click doesn't produce two watermarked
 * copies. It is NOT a promise that the key is spent for good. Once a job has
 * failed, or its output has expired and been deleted, there is nothing left
 * to hand back, so the same key has to be free to start a fresh job -
 * otherwise a client that retries with a stable key, which is exactly what a
 * well-behaved client does, could never recover from a single failure.
 * <p>
 * The rule lives in the partial unique index (ux_watermark_jobs_idempotency,
 * V68) so it holds under a race between two concurrent requests, not only in
 * the service's own lookup. These tests go at the index directly.
 */
@SpringBootTest(properties = "dms.watermark.enabled=true")
class WatermarkJobIdempotencyIT {

    @Autowired
    private WatermarkJobRepository repository;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void twoLiveJobsCannotShareAKey_soADoubleClickCanOnlyEverProduceOneCopy() {
        UUID tenantId = tenant();
        UUID user = UUID.randomUUID();
        repository.saveAndFlush(job(tenantId, user, "double-click", WatermarkJobStatus.pending));

        assertThatThrownBy(() -> repository.saveAndFlush(
                job(tenantId, user, "double-click", WatermarkJobStatus.pending)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aKeyIsStillTakenWhileTheJobIsProcessingOrCompleted() {
        UUID tenantId = tenant();
        for (WatermarkJobStatus live : new WatermarkJobStatus[]{
                WatermarkJobStatus.processing, WatermarkJobStatus.completed}) {
            UUID user = UUID.randomUUID();
            repository.saveAndFlush(job(tenantId, user, "in-flight", live));
            assertThatThrownBy(() -> repository.saveAndFlush(
                    job(tenantId, user, "in-flight", WatermarkJobStatus.pending)))
                    .describedAs("a %s job should still hold its key", live)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void aFailedJobReleasesItsKey_soARetryWithTheSameKeyStartsAfresh() {
        UUID tenantId = tenant();
        UUID user = UUID.randomUUID();
        WatermarkJob failed = repository.saveAndFlush(
                job(tenantId, user, "retry-me", WatermarkJobStatus.failed));

        WatermarkJob retried = repository.saveAndFlush(
                job(tenantId, user, "retry-me", WatermarkJobStatus.pending));

        assertThat(retried.getJobId()).isNotEqualTo(failed.getJobId());
        // The failed one stays as history rather than being overwritten.
        assertThat(repository.findById(failed.getJobId())).isPresent();
        assertThat(countFor(tenantId, user, "retry-me")).isEqualTo(2);
    }

    @Test
    void anExpiredJobReleasesItsKeyToo_becauseItsOutputIsGone() {
        UUID tenantId = tenant();
        UUID user = UUID.randomUUID();
        repository.saveAndFlush(job(tenantId, user, "expired-key", WatermarkJobStatus.expired));

        assertThatCode(() -> repository.saveAndFlush(
                job(tenantId, user, "expired-key", WatermarkJobStatus.pending)))
                .doesNotThrowAnyException();
        assertThat(countFor(tenantId, user, "expired-key")).isEqualTo(2);
    }

    @Test
    void theLiveLookupIgnoresDeadJobsAndFindsOnlyTheOneStillWorthHandingBack() {
        UUID tenantId = tenant();
        UUID user = UUID.randomUUID();
        repository.saveAndFlush(job(tenantId, user, "history", WatermarkJobStatus.failed));
        repository.saveAndFlush(job(tenantId, user, "history", WatermarkJobStatus.expired));

        // Three rows share the key; exactly one of them is live.
        assertThat(repository.findLiveByIdempotencyKey(tenantId, user, "history")).isEmpty();

        WatermarkJob live = repository.saveAndFlush(job(tenantId, user, "history", WatermarkJobStatus.completed));
        assertThat(repository.findLiveByIdempotencyKey(tenantId, user, "history"))
                .isPresent()
                .get()
                .extracting(WatermarkJob::getJobId)
                .isEqualTo(live.getJobId());
    }

    @Test
    void aKeyIsScopedToOnePersonInOneTenant() {
        UUID first = tenant();
        UUID second = tenant();
        UUID user = UUID.randomUUID();

        repository.saveAndFlush(job(first, user, "shared-word", WatermarkJobStatus.pending));
        // The same person's id in another tenant, and another person in the
        // same tenant, both get their own key space.
        assertThatCode(() -> repository.saveAndFlush(job(second, user, "shared-word", WatermarkJobStatus.pending)))
                .doesNotThrowAnyException();
        assertThatCode(() -> repository.saveAndFlush(
                job(first, UUID.randomUUID(), "shared-word", WatermarkJobStatus.pending)))
                .doesNotThrowAnyException();
    }

    @Test
    void jobsWithoutAKeyNeverCollide() {
        UUID tenantId = tenant();
        UUID user = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            repository.saveAndFlush(job(tenantId, user, null, WatermarkJobStatus.pending));
        }
        assertThat(jdbc.queryForObject("select count(*) from watermark_jobs where tenant_id = ?",
                Integer.class, tenantId)).isEqualTo(3);
    }

    private int countFor(UUID tenantId, UUID user, String key) {
        Integer count = jdbc.queryForObject("select count(*) from watermark_jobs "
                + "where tenant_id = ? and requested_by = ? and idempotency_key = ?", Integer.class,
                tenantId, user, key);
        return count == null ? 0 : count;
    }

    private static WatermarkJob job(UUID tenantId, UUID requestedBy, String key, WatermarkJobStatus status) {
        return WatermarkJob.builder()
                .jobId(UUID.randomUUID())
                .tenantId(tenantId)
                .requestedBy(requestedBy)
                .documentId(UUID.randomUUID())
                .versionId(UUID.randomUUID())
                .requestSnapshot("{\"text\":\"CONFIDENTIAL\"}")
                .idempotencyKey(key)
                .requestHash("0".repeat(64))
                .status(status)
                .build();
    }

    private UUID tenant() {
        return tenantRepository.save(Tenant.builder()
                .tenantName("Watermark idempotency tenant " + UUID.randomUUID())
                .domain("watermark-idempotency-" + UUID.randomUUID() + ".invalid")
                .subscriptionTier("standard")
                .status(Tenant.TenantStatus.active)
                .build()).getTenantId();
    }
}
