package com.oak.dms.audit.dto;

import com.oak.dms.audit.enums.AuditActorType;
import com.oak.dms.audit.enums.AuditAuthMethod;
import com.oak.dms.audit.enums.AuditClientType;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.enums.AuditSeverity;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Full public API representation of one {@link com.oak.dms.audit.entity.AuditEvent}
 * row, for {@code GET /api/v1/audit-events/{id}} and
 * {@code GET /api/v1/platform/audit-events/{id}} - the same fields as
 * {@link AuditEventResponse} plus previousValue/newValue (already redacted
 * for sensitive fields at write time - see
 * {@code com.oak.dms.audit.helper.AuditValueRedactor} - and returned here as real
 * embedded JSON, not a JSON-encoded string, since they were stored as
 * pre-serialized JSON strings) and userAgent, both excluded from the list
 * response as too large/verbose for a paginated table row.
 *
 * previousValue/newValue are typed as plain {@code Object} (a generic
 * Map/List/String/Number/Boolean/null graph), not
 * {@code com.fasterxml.jackson.databind.JsonNode} - this app's actual
 * HTTP message-converter ObjectMapper is a different Jackson major
 * version than the one on the classpath under that package (a real bug
 * hit during end-to-end testing: a JsonNode serialized through the
 * "wrong" mapper isn't recognized as a tree type at all and gets
 * serialized via generic bean introspection over its own is-getters,
 * e.g. {@code {"array": false, "bigDecimal": false, ...}} instead of the
 * actual value). A plain Object graph has no such ambiguity - see
 * {@code AuditEventServiceImpl#toGenericValue}.
 */
public record AuditEventDetailResponse(
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
        Object previousValue,
        Object newValue,
        String reason,
        String sessionId,
        AuditAuthMethod authMethod,
        String permissionCode,
        String dataClassification,
        Boolean legalHoldFlag,
        String ipAddress,
        String userAgent,
        AuditClientType clientType,
        UUID correlationId,
        UUID requestId,
        AuditSeverity severity,
        String recordHash,
        String previousHash
) {
}
