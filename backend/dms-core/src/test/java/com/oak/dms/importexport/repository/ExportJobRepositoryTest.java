package com.oak.dms.importexport.repository;

import com.oak.dms.importexport.entity.ExportJob;
import com.oak.dms.importexport.enums.ExportJobStatus;
import com.oak.dms.importexport.enums.ExportType;
import com.oak.dms.importexport.service.ExportJobService;
import com.oak.dms.tenant.Tenant;
import com.oak.dms.tenant.TenantRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * export_jobs data access against the real schema (V41 applied by Flyway at
 * context start), each test in a transaction that is rolled back. Named
 * *Test, not *IT, so CI's `mvn verify` runs it - it only needs the Postgres
 * CI already provides for DmsCoreApplicationTests.
 */
@SpringBootTest
@Transactional
class ExportJobRepositoryTest {

    @Autowired
    private ExportJobRepository repository;
    @Autowired
    private ExportJobService service;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID tenantId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        tenantId = newTenant();
        userId = newUser(tenantId);
    }

    @Test
    void create_generatesIdAndDefaults() {
        UUID correlationId = UUID.randomUUID();

        ExportJob saved = repository.saveAndFlush(ExportJob.builder()
                .tenantId(tenantId).requestedBy(userId)
                .exportType(ExportType.single_document).correlationId(correlationId).build());

        assertThat(saved.getExportJobId()).isNotNull();
        ExportJob reloaded = repository.findById(saved.getExportJobId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ExportJobStatus.pending);
        assertThat(reloaded.getExportType()).isEqualTo(ExportType.single_document);
        assertThat(reloaded.getCorrelationId()).isEqualTo(correlationId);
        assertThat(reloaded.getFileObjectKey()).isNull();
        assertThat(reloaded.getExpiresAt()).isNull();
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void getById_isTenantScoped() {
        ExportJob job = service.create(tenantId, userId, ExportType.report_data, null);
        UUID otherTenant = newTenant();

        assertThat(repository.findByExportJobIdAndTenantId(job.getExportJobId(), tenantId)).isPresent();
        assertThat(repository.findByExportJobIdAndTenantId(job.getExportJobId(), otherTenant)).isEmpty();
    }

    @Test
    void updateStatus_persistsNewStatusAndUpdatedAt() {
        ExportJob job = service.create(tenantId, userId, ExportType.bulk_folder, null);
        OffsetDateTime firstUpdate = job.getUpdatedAt();

        service.updateStatus(job.getExportJobId(), tenantId, "processing");
        repository.flush();

        ExportJob reloaded = repository.findById(job.getExportJobId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ExportJobStatus.processing);
        assertThat(reloaded.getUpdatedAt()).isAfterOrEqualTo(firstUpdate);
        // Stored as the lowercase value the CHECK constraint allows.
        assertThat(jdbc.queryForObject("select status from export_jobs where export_job_id = ?",
                String.class, job.getExportJobId())).isEqualTo("processing");
    }

    @Test
    void listByTenantAndStatus_filtersOnBothAndOrdersNewestFirst() {
        // Explicit, clearly different created_at values: two back-to-back
        // now() calls can return the same instant (coarse Windows clock),
        // which would make "newest first" a coin toss.
        OffsetDateTime now = OffsetDateTime.now();
        ExportJob older = pendingJobCreatedAt(now.minusHours(2));
        ExportJob newer = pendingJobCreatedAt(now.minusMinutes(5));
        ExportJob completed = service.create(tenantId, userId, ExportType.bulk_folder, null);
        service.updateStatus(completed.getExportJobId(), tenantId, ExportJobStatus.completed);
        UUID otherTenant = newTenant();
        service.create(otherTenant, newUser(otherTenant), ExportType.bulk_folder, null);
        repository.flush();

        List<ExportJob> pending = repository.findByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, ExportJobStatus.pending);

        assertThat(pending).extracting(ExportJob::getExportJobId)
                .containsExactly(newer.getExportJobId(), older.getExportJobId());
        assertThat(service.listByTenantAndStatus(tenantId, "completed"))
                .extracting(ExportJob::getExportJobId).containsExactly(completed.getExportJobId());
    }

    @Test
    void myExports_onlyTheRequestersJobsInTheirTenant_pagedNewestFirst() {
        OffsetDateTime now = OffsetDateTime.now();
        ExportJob mineOld = pendingJobCreatedAt(now.minusHours(3));
        ExportJob mineNew = pendingJobCreatedAt(now.minusMinutes(1));
        UUID colleague = newUser(tenantId);
        service.create(tenantId, colleague, ExportType.bulk_folder, null);            // same tenant, other user
        UUID otherTenant = newTenant();
        service.create(otherTenant, newUser(otherTenant), ExportType.bulk_folder, null); // other tenant
        repository.flush();
        PageRequest firstPage = PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "createdAt"));

        var page = repository.findByTenantIdAndRequestedBy(tenantId, userId, firstPage);

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(ExportJob::getExportJobId).containsExactly(mineNew.getExportJobId());
        assertThat(repository.findByTenantIdAndRequestedBy(tenantId, userId, firstPage.next()).getContent())
                .extracting(ExportJob::getExportJobId).containsExactly(mineOld.getExportJobId());
        assertThat(repository.findByTenantIdAndRequestedByAndStatus(tenantId, userId, ExportJobStatus.completed, firstPage)
                .getTotalElements()).isZero();
    }

    @Test
    void findExpired_returnsOnlyPastDeadlines_oldestFirst() {
        OffsetDateTime now = OffsetDateTime.now();
        ExportJob longExpired = jobExpiringAt(now.minusDays(2));
        ExportJob justExpired = jobExpiringAt(now.minusMinutes(1));
        jobExpiringAt(now.plusHours(1));      // not yet
        jobExpiringAt(null);                   // no deadline set - never matches
        repository.flush();

        List<ExportJob> expired = service.findExpired(now);

        assertThat(expired).extracting(ExportJob::getExportJobId)
                .contains(longExpired.getExportJobId(), justExpired.getExportJobId());
        assertThat(expired).allSatisfy(j -> assertThat(j.getExpiresAt()).isBefore(now));
        assertThat(expired).extracting(ExportJob::getExportJobId)
                .containsSubsequence(longExpired.getExportJobId(), justExpired.getExportJobId());
    }

    // The next four bypass the application layer on purpose: the database must
    // still refuse the row. One violation per test - in Postgres a failed
    // statement aborts the surrounding (rolled-back) test transaction.
    // setUp flushes the tenant and user, so each failure really is the named
    // constraint and not a missing parent row.

    @Test
    void dbCheck_rejectsUnknownExportType() {
        assertThatThrownBy(() -> jdbc.update(
                "insert into export_jobs (tenant_id, requested_by, export_type) values (?, ?, 'everything')",
                tenantId, userId)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_export_jobs_export_type");
    }

    @Test
    void dbCheck_rejectsUnknownStatus() {
        assertThatThrownBy(() -> jdbc.update(
                "insert into export_jobs (tenant_id, requested_by, export_type, status) values (?, ?, 'report_data', 'done')",
                tenantId, userId)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_export_jobs_status");
    }

    @Test
    void dbForeignKey_rejectsUnknownTenant() {
        assertThatThrownBy(() -> jdbc.update(
                "insert into export_jobs (tenant_id, requested_by, export_type) values (?, ?, 'report_data')",
                UUID.randomUUID(), userId)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("export_jobs_tenant_id_fkey");
    }

    @Test
    void dbForeignKey_rejectsUnknownUser() {
        assertThatThrownBy(() -> jdbc.update(
                "insert into export_jobs (tenant_id, requested_by, export_type) values (?, ?, 'report_data')",
                tenantId, UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("export_jobs_requested_by_fkey");
    }

    private ExportJob pendingJobCreatedAt(OffsetDateTime createdAt) {
        // created_at is insertable, just not updatable - so it has to be set here.
        return repository.save(ExportJob.builder()
                .tenantId(tenantId).requestedBy(userId).exportType(ExportType.bulk_folder)
                .createdAt(createdAt).updatedAt(createdAt).build());
    }

    private ExportJob jobExpiringAt(OffsetDateTime expiresAt) {
        ExportJob job = service.create(tenantId, userId, ExportType.single_document, null);
        job.setExpiresAt(expiresAt);
        return repository.save(job);
    }

    private UUID newTenant() {
        return tenantRepository.saveAndFlush(Tenant.builder()
                .tenantName("Export jobs test tenant " + UUID.randomUUID())
                .domain("export-jobs-test-" + UUID.randomUUID() + ".invalid")
                .subscriptionTier("standard")
                .status(Tenant.TenantStatus.active)
                .build()).getTenantId();
    }

    private UUID newUser(UUID tenant) {
        UUID keycloakId = UUID.randomUUID();
        return userRepository.saveAndFlush(User.builder()
                .tenantId(tenant)
                .keycloakId(keycloakId)
                .email(keycloakId + "@export-jobs-test.invalid")
                .username(keycloakId.toString())
                .firstName("Export")
                .lastName("Tester")
                .displayName("Export Tester")
                .status(User.UserStatus.active)
                .build()).getUserId();
    }
}
