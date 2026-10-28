package com.oak.dms.watermark.repository;

import com.oak.dms.watermark.entity.WatermarkTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@link WatermarkTemplate}. Reads come in two shapes, and
 * the difference matters:
 * <ul>
 *   <li><b>visible</b> - the tenant's own templates plus the system ones
 *       ({@code tenant_id is null}). What a user choosing a watermark sees.</li>
 *   <li><b>owned</b> - the tenant's own only. What an admin may edit; a
 *       system template is never in this set, so editing one is a 404 at the
 *       repository rather than a check somebody can forget.</li>
 * </ul>
 */
public interface WatermarkTemplateRepository extends JpaRepository<WatermarkTemplate, UUID> {

    /** One template the tenant may USE - its own or a system one. */
    @Query("""
            select t from WatermarkTemplate t
            where t.templateId = :templateId
              and (t.tenantId = :tenantId or t.tenantId is null)
              and t.deleted = false
            """)
    Optional<WatermarkTemplate> findVisible(@Param("templateId") UUID templateId, @Param("tenantId") UUID tenantId);

    /**
     * One template the tenant OWNS - the only kind an admin may change.
     * A system template (tenant_id null) deliberately doesn't match, so
     * {@code WatermarkTemplateService} can't accidentally hand one to an
     * update path. TENANT_ADMIN bypasses permission checks, not this.
     */
    Optional<WatermarkTemplate> findByTemplateIdAndTenantIdAndDeletedFalse(UUID templateId, UUID tenantId);

    /**
     * Everything the tenant may use, system templates included, system ones
     * first and then by label. {@code includeInactive} false is the ordinary
     * user's list; admins pass true to see what they have retired.
     */
    @Query("""
            select t from WatermarkTemplate t
            where (t.tenantId = :tenantId or t.tenantId is null)
              and t.deleted = false
              and (:includeInactive = true or t.active = true)
            order by t.systemDefault desc, lower(t.label) asc
            """)
    List<WatermarkTemplate> findVisibleForTenant(@Param("tenantId") UUID tenantId,
                                                 @Param("includeInactive") boolean includeInactive);

    /**
     * Is this label already taken in the tenant's own scope? Mirrors the
     * ux_watermark_templates_label unique index so the user gets a sentence
     * instead of a constraint violation - the index is still what guarantees
     * it under a race.
     */
    boolean existsByTenantIdAndLabelIgnoreCaseAndDeletedFalse(UUID tenantId, String label);
}
