package com.oak.dms.audit.service;

import com.oak.dms.audit.dto.AuditEventDetailResponse;
import com.oak.dms.audit.dto.AuditEventResponse;
import com.oak.dms.audit.dto.AuditEventSearchCriteria;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Read side of the audit trail - see {@link AuditService} for the write
 * side. The tenant-scoped methods ({@link #search}/{@link #getById}) and
 * the cross-tenant, PLATFORM_ADMIN-only ones
 * ({@link #searchAcrossTenants}/{@link #getByIdAcrossTenants}) share this
 * one service/repository/Specification layer, since the underlying query
 * logic is identical either way - only the authorization model and
 * whether a tenant restriction is forced differ, and that difference is
 * enforced by which controller/method a caller can reach, not duplicated
 * across two services.
 */
public interface AuditEventService {

    /** Searches the caller's own tenant's audit trail; see {@link AuditEventSearchCriteria} for the filters. */
    Page<AuditEventResponse> search(AuditEventSearchCriteria criteria, Pageable pageable);

    /** Gets one event by id; 404s if it belongs to another tenant (or to no tenant - a platform-level event). */
    AuditEventDetailResponse getById(UUID id);

    /**
     * PLATFORM_ADMIN-only: null tenantId means every tenant plus
     * platform-level (tenant_id null) events; a non-null tenantId narrows
     * to that one tenant only. Authorization is enforced by the caller
     * (see {@code PlatformAuditEventController}), not here.
     */
    Page<AuditEventResponse> searchAcrossTenants(UUID tenantId, AuditEventSearchCriteria criteria, Pageable pageable);

    /** PLATFORM_ADMIN-only: no tenant-ownership check at all - any event, including platform-level ones. */
    AuditEventDetailResponse getByIdAcrossTenants(UUID id);

    /**
     * CSV export of the caller's own tenant's audit trail, matching
     * {@link #search}'s same filters and tenant scoping - capped at
     * {@code EXPORT_ROW_LIMIT} rows (newest first), and itself recorded as
     * an AUDIT_LOG_EXPORTED audit event before returning, per the schema
     * spec's own requirement that exporting the trail be auditable too.
     */
    String exportCsv(AuditEventSearchCriteria criteria);
}
