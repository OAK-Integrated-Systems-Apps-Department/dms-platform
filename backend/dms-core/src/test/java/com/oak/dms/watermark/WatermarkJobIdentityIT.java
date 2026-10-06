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
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the guarantee the {@code {watermarkId}} placeholder rests on: the id
 * drawn onto the page is the id stored in the row.
 * <p>
 * The watermark text is resolved in the request thread, before the job row
 * is written, so {@link WatermarkJob} takes an ASSIGNED id rather than a
 * generated one. If anyone swaps it back to {@code @UuidGenerator}, the
 * value drawn on a document and the value in the audit trail would silently
 * stop being the same thing - and a leaked copy would point at a job that
 * doesn't exist. That failure is invisible by inspection, which is what this
 * test is for.
 */
@SpringBootTest(properties = "dms.watermark.enabled=true")
class WatermarkJobIdentityIT {

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
    void theIdAssignedBeforeTheInsertIsTheIdThatGetsStored() {
        UUID tenantId = tenant();
        // Exactly what WatermarkService does: mint the id, resolve the text
        // against it, then write the row.
        UUID jobId = UUID.randomUUID();
        String drawnOnThePage = jobId.toString().substring(0, 8);

        WatermarkJob saved = repository.saveAndFlush(job(jobId, tenantId, drawnOnThePage));

        assertThat(saved.getJobId()).isEqualTo(jobId);
        assertThat(jdbc.queryForObject("select job_id from watermark_jobs where job_id = ?", UUID.class, jobId))
                .isEqualTo(jobId);
        // The whole point: what a reader of the leaked copy types in matches.
        assertThat(saved.getJobId().toString()).startsWith(drawnOnThePage);
    }

    @Test
    void savingAnAssignedIdInsertsOnceRatherThanLeavingTheRowBehindUnderAFreshId() {
        UUID tenantId = tenant();
        UUID jobId = UUID.randomUUID();

        repository.saveAndFlush(job(jobId, tenantId, jobId.toString().substring(0, 8)));

        assertThat(repository.findById(jobId)).isPresent();
        assertThat(jdbc.queryForObject("select count(*) from watermark_jobs where tenant_id = ?",
                Integer.class, tenantId)).isEqualTo(1);
    }

    @Test
    void aLoadedJobIsNoLongerNewSoTheWorkerUpdatesItInsteadOfInsertingAgain() {
        UUID tenantId = tenant();
        UUID jobId = UUID.randomUUID();
        repository.saveAndFlush(job(jobId, tenantId, "abcdef12"));

        // The worker's claim(): load, change status, save.
        WatermarkJob loaded = repository.findById(jobId).orElseThrow();
        assertThat(loaded.isNew()).isFalse();
        loaded.setStatus(WatermarkJobStatus.processing);
        repository.saveAndFlush(loaded);

        assertThat(jdbc.queryForObject("select count(*) from watermark_jobs where tenant_id = ?",
                Integer.class, tenantId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from watermark_jobs where job_id = ?", String.class, jobId))
                .isEqualTo("processing");
    }

    @Test
    void aFreshlySavedJobIsAlsoNoLongerNew() {
        UUID tenantId = tenant();
        WatermarkJob saved = repository.saveAndFlush(job(UUID.randomUUID(), tenantId, "0f0f0f0f"));
        assertThat(saved.isNew()).isFalse();
    }

    private static WatermarkJob job(UUID jobId, UUID tenantId, String watermarkId) {
        return WatermarkJob.builder()
                .jobId(jobId)
                .tenantId(tenantId)
                .requestedBy(UUID.randomUUID())
                .documentId(UUID.randomUUID())
                .versionId(UUID.randomUUID())
                .requestSnapshot("{\"text\":\"CONFIDENTIAL " + watermarkId + "\"}")
                .status(WatermarkJobStatus.pending)
                .build();
    }

    private UUID tenant() {
        return tenantRepository.save(Tenant.builder()
                .tenantName("Watermark job id test tenant " + UUID.randomUUID())
                .domain("watermark-job-id-test-" + UUID.randomUUID() + ".invalid")
                .subscriptionTier("standard")
                .status(Tenant.TenantStatus.active)
                .build()).getTenantId();
    }
}
