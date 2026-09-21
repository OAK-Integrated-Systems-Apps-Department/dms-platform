package com.oak.dms.audit.controller;

import com.oak.dms.audit.dto.AuditEventDetailResponse;
import com.oak.dms.audit.dto.AuditEventResponse;
import com.oak.dms.audit.dto.AuditEventSearchCriteria;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.enums.AuditSeverity;
import com.oak.dms.audit.service.AuditEventService;
import com.oak.dms.common.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Read-only search over the caller's own tenant's audit trail - always
 * scoped to {@code TenantContext}, with no tenant override accepted from
 * the request at all, same as every other tenant-scoped list endpoint in
 * this app. A cross-tenant, PLATFORM_ADMIN-only view (also surfacing
 * platform-level events with no owning tenant) is deliberately a separate
 * endpoint with its own authorization model - see
 * {@link PlatformAuditEventController} - rather than a special case bolted
 * onto this one.
 */
@RestController
@RequestMapping("/api/v1/audit-events")
@RequiredArgsConstructor
@Tag(name = "Audit Events", description = "Search the caller's own tenant's immutable audit trail")
public class AuditEventController {

    /**
     * Shared with {@link PlatformAuditEventController#search} so both
     * audit-search endpoints default to the same page size/sort - kept
     * here as the one place that decides it, rather than the same literal
     * "25"/"occurredAt" being retyped at each {@code @PageableDefault}.
     */
    static final int DEFAULT_PAGE_SIZE = 25;
    static final String DEFAULT_SORT_FIELD = "occurredAt";

    private final AuditEventService auditEventService;

    @GetMapping
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.PermissionCodes).ABDR_VIEW_AUDIT_HISTORY)")
    @Operation(summary = "Search the caller's tenant audit trail",
            description = "All filters are optional and combined with AND. occurredFrom/occurredTo bound "
                    + "occurred_at (both inclusive) and accept ISO-8601 date-times, e.g. "
                    + "2026-01-01T00:00:00Z. Defaults to newest-first (occurredAt desc) if no sort is given.")
    public PageResponse<AuditEventResponse> search(
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
            @PageableDefault(size = DEFAULT_PAGE_SIZE, sort = DEFAULT_SORT_FIELD, direction = Sort.Direction.DESC) Pageable pageable) {
        AuditEventSearchCriteria criteria = new AuditEventSearchCriteria(
                module, action, actorId, objectType, objectId, outcome, severity, occurredFrom, occurredTo, correlationId);
        return PageResponse.from(auditEventService.search(criteria, pageable));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.PermissionCodes).ABDR_VIEW_AUDIT_HISTORY)")
    @Operation(summary = "Get one audit event by id, within the caller's own tenant",
            description = "404s for an id belonging to another tenant or to no tenant at all (a "
                    + "platform-level event) - same tenant-boundary-never-leaks-existence handling as every "
                    + "other resource in this app. Includes previousValue/newValue and userAgent, deliberately "
                    + "left out of the list response.")
    public AuditEventDetailResponse getById(@PathVariable UUID id) {
        return auditEventService.getById(id);
    }

    @GetMapping(value = "/export", produces = "text/csv")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.PermissionCodes).ABDR_EXPORT_AUDIT_REPORTS)")
    @Operation(summary = "Export the caller's tenant audit trail as CSV",
            description = "Same filters as GET /api/v1/audit-events (no page/size/sort - always newest-first, "
                    + "capped at 10,000 rows). Gated by a separate abdr.export-audit-reports permission, not "
                    + "abdr.view-audit-history - exporting is a stricter capability than viewing on-screen. "
                    + "The export itself is recorded as its own AUDIT_LOG_EXPORTED audit event.")
    public ResponseEntity<String> exportCsv(
            @RequestParam(required = false) List<String> module,
            @RequestParam(required = false) List<String> action,
            @RequestParam(required = false) List<UUID> actorId,
            @RequestParam(required = false) List<String> objectType,
            @RequestParam(required = false) UUID objectId,
            @RequestParam(required = false) AuditOutcome outcome,
            @RequestParam(required = false) AuditSeverity severity,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime occurredFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime occurredTo,
            @RequestParam(required = false) UUID correlationId) {
        AuditEventSearchCriteria criteria = new AuditEventSearchCriteria(
                module, action, actorId, objectType, objectId, outcome, severity, occurredFrom, occurredTo, correlationId);
        String csv = auditEventService.exportCsv(criteria);
        String filename = "audit-log-" + FILENAME_TIMESTAMP.format(OffsetDateTime.now()) + ".csv";

        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                .body(csv);
    }

    private static final DateTimeFormatter FILENAME_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");
}
