package com.oak.dms.reporting.repository;

import com.oak.dms.reporting.entity.ReportSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReportScheduleRepository extends JpaRepository<ReportSchedule, UUID>, JpaSpecificationExecutor<ReportSchedule> {

    Optional<ReportSchedule> findByScheduleIdAndTenantId(UUID scheduleId, UUID tenantId);

    /** Active, paused or paused after failures: what counts against a person's or an organisation's limit. */
    @Query("""
            select count(s) from ReportSchedule s
            where s.tenantId = :tenant and s.ownerUserId = :owner and s.status in ('active', 'paused', 'paused_failed')""")
    long countLiveByOwner(@Param("tenant") UUID tenantId, @Param("owner") UUID ownerUserId);

    /** Live schedules per template: rows of {template id, count}. */
    @Query("""
            select s.templateId, count(s) from ReportSchedule s
            where s.tenantId = :tenant and s.status in ('active', 'paused', 'paused_failed')
            group by s.templateId""")
    List<Object[]> countLiveByTemplate(@Param("tenant") UUID tenantId);

    @Query("""
            select count(s) from ReportSchedule s
            where s.tenantId = :tenant and s.status in ('active', 'paused', 'paused_failed')""")
    long countLiveByTenant(@Param("tenant") UUID tenantId);

    /**
     * The schedules that are due, locked for this transaction only: another scheduler instance running the same query at the same
     * moment skips these rows and takes others, so a schedule is never handled twice.
     */
    @Query(value = """
            select * from report_schedules
            where status = 'active' and next_run_at <= now()
            order by next_run_at
            limit :batch
            for update skip locked""", nativeQuery = true)
    List<ReportSchedule> lockDue(@Param("batch") int batch);
}
