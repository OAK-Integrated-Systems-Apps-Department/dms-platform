package com.oak.dms.audit;

import com.oak.dms.audit.dto.AuditEventDetailResponse;
import com.oak.dms.audit.dto.AuditEventResponse;
import com.oak.dms.audit.dto.AuditEventSearchCriteria;
import com.oak.dms.common.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Cross-tenant audit search, restricted to PLATFORM_ADMIN - the same
 * native-role gate {@code PermissionController} already uses for its own
 * platform-wide writes, deliberately not the {@code abdr.view-audit-history}
 * permission code {@link AuditEventController} uses (that code is
 * delegable to a tenant's own custom roles via Role Permission
 * Configuration, which must never be able to grant visibility into other
 * tenants' data - only the fixed PLATFORM_ADMIN realm role can).
 * <p>
 * Kept as its own controller/path rather than folded into
 * {@link AuditEventController}: the two endpoints have genuinely different
 * authorization models (a delegable permission code vs. a fixed platform
 * role) and different tenant-scoping behavior (always forced vs.
 * caller-controlled) - overloading one endpoint with both would make
 * neither easy to reason about or to gate correctly in {@code SecurityConfig}/
 * tests. The query logic itself is shared - see {@link AuditEventServiceImpl}.
 */
@RestController
@RequestMapping("/api/v1/platform/audit-events")
@RequiredArgsConstructor
@Tag(name = "Platform Audit Events", description = "PLATFORM_ADMIN-only cross-tenant audit search")
public class PlatformAuditEventController {

    private final AuditEventService auditEventService;

    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "Search the audit trail across every tenant",
            description = "Same filters as GET /api/v1/audit-events, plus tenantId. Leave tenantId unset to "
                    + "see every tenant plus any platform-level events (ones with no single owning tenant, "
                    + "invisible through the tenant-scoped endpoint - see AuditEventBuilder#platformLevel for "
                    + "which actions can produce one); supply it to narrow to one tenant.")
    public PageResponse<AuditEventResponse> search(
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(required = false) List<String> module,
            @RequestParam(required = false) List<String> action,
            @RequestParam(required = false) List<UUID> actorId,
            @RequestParam(required = false) List<String> objectType,
            @RequestParam(required = false) UUID objectId,
            @RequestParam(required = false) AuditOutcome outcome,
            @RequestParam(required = false) AuditSeverity severity,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime occurredFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime occurredTo,
            @RequestParam(required = false) UUID correlationId,
            @PageableDefault(size = AuditEventController.DEFAULT_PAGE_SIZE, sort = AuditEventController.DEFAULT_SORT_FIELD,
                    direction = Sort.Direction.DESC) Pageable pageable) {
        AuditEventSearchCriteria criteria = new AuditEventSearchCriteria(
                module, action, actorId, objectType, objectId, outcome, severity, occurredFrom, occurredTo, correlationId);
        return PageResponse.from(auditEventService.searchAcrossTenants(tenantId, criteria, pageable));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "Get one audit event by id, across any tenant",
            description = "Unlike GET /api/v1/audit-events/{id}, this never 404s for tenant-boundary reasons - "
                    + "a PLATFORM_ADMIN can look up any event, including a platform-level one.")
    public AuditEventDetailResponse getById(@PathVariable UUID id) {
        return auditEventService.getByIdAcrossTenants(id);
    }
}
