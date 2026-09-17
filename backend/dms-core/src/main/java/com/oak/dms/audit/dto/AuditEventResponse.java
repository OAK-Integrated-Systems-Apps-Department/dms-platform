package com.oak.dms.audit.dto;

import com.oak.dms.audit.AuditActorType;
import com.oak.dms.audit.AuditAuthMethod;
import com.oak.dms.audit.AuditClientType;
import com.oak.dms.audit.AuditOutcome;
import com.oak.dms.audit.AuditSeverity;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Public API representation of one {@link com.oak.dms.audit.AuditEvent} row
 * for the search/list view.
 *
 * Deliberately excludes previousValue/newValue (the full before/after JSON
 * snapshots) and userAgent - large/verbose fields that don't belong in a
 * paginated table response. A future {@code GET /api/v1/audit-events/{id}}
 * detail endpoint is the natural place for those, once the frontend needs a
 * per-record drill-down view.
 */
public record AuditEventResponse(
        UUID id,
        UUID tenantId,
        UUID actorId,
        AuditActorType actorType,
        String actorDisplayName,
        UUID impersonatedBy,
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
        UUID sessionId,
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
