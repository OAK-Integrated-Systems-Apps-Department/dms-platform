package com.oak.dms.reporting.repository;

import com.oak.dms.reporting.entity.ReportTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReportTemplateRepository extends JpaRepository<ReportTemplate, UUID> {

    Optional<ReportTemplate> findByTemplateIdAndTenantIdAndDeletedAtIsNull(UUID templateId, UUID tenantId);

    List<ReportTemplate> findByTenantIdAndTemplateIdInAndDeletedAtIsNull(UUID tenantId, Collection<UUID> templateIds);

    /** Official templates, and any template a live schedule runs: the ones whose use of a field matters when it is switched off. */
    @Query("""
            select t from ReportTemplate t
            where t.tenantId = :tenant and t.deletedAt is null and (t.scope = 'official' or t.templateId in (
                select s.templateId from ReportSchedule s
                where s.tenantId = :tenant and s.status in ('active', 'paused', 'paused_failed')))""")
    List<ReportTemplate> findOfficialOrScheduled(@Param("tenant") UUID tenantId);

    /** What counts against a person's template limit: their own private and shared templates. */
    long countByTenantIdAndOwnerUserIdAndDeletedAtIsNull(UUID tenantId, UUID ownerUserId);

    @Query("""
            select count(t) > 0 from ReportTemplate t
            where t.tenantId = :tenant and t.deletedAt is null and lower(t.name) = lower(:name)
              and t.templateId <> :except and t.scope <> 'official' and t.ownerUserId = :owner""")
    boolean ownerNameTaken(@Param("tenant") UUID tenantId, @Param("owner") UUID ownerUserId, @Param("name") String name,
                           @Param("except") UUID except);

    @Query("""
            select count(t) > 0 from ReportTemplate t
            where t.tenantId = :tenant and t.deletedAt is null and lower(t.name) = lower(:name)
              and t.templateId <> :except and t.scope = 'official'""")
    boolean officialNameTaken(@Param("tenant") UUID tenantId, @Param("name") String name, @Param("except") UUID except);
}
