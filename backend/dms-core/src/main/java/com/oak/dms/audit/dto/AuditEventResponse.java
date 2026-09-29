package com.oak.dms.audit.dto;

import com.oak.dms.audit.enums.AuditActorType;
import com.oak.dms.audit.enums.AuditAuthMethod;
import com.oak.dms.audit.enums.AuditClientType;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.enums.AuditSeverity;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Public API representation of one {@link com.oak.dms.audit.entity.AuditEvent} row
 * for the search/list view.
 *
 * Deliberately excludes previousValue/newValue (the full before/after JSON
 * snapshots) and userAgent - large/verbose fields that don't belong in a
 * paginated table response. {@link AuditEventDetailResponse} (returned by
 * {@code GET /api/v1/audit-events/{id}}) carries those instead, for the
 * frontend's per-record drill-down view.
 */
public record AuditEventResponse(
        UUID id,
        UUID tenantId,
        UUID actorId,
        AuditActorType actorType,
        String actorDisplayName,
        UUID impersonatedBy,
        String impersonatedByDisplayName,
        String action,
        String module,
        String objectType,
        UUID objectId,
        String resourceName,
        Integer objectVersion,
        AuditOutcome outcome,
        String failureReason,
        OffsetDateTime occurredAt,
        String reason,
        String sessionId,
        AuditAuthMethod authMethod,
        String permissionCode,
        String dataClassification,
        Boolean legalHoldFlag,
        String ipAddress,
        AuditClientType clientType,
        UUID correlationId,
        UUID requestId,
        AuditSeverity severity,
        String recordHash,
        String previousHash
) {
}
