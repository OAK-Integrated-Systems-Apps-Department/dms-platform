package com.oak.dms.audit.dto;

import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.enums.AuditSeverity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The optional search filters shared by both the tenant-scoped
 * {@code GET /api/v1/audit-events} and the PLATFORM_ADMIN-only
 * {@code GET /api/v1/platform/audit-events}, all combined with AND when
 * present. Every field may be null - see {@code AuditEventSpecifications},
 * which treats a null (or empty list) field as "no constraint" rather than
 * "match nothing".
 *
 * {@code modules}/{@code actions}/{@code actorIds}/{@code objectTypes} are
 * lists (an event matches if its own value is any one of them - an OR
 * within that field, still ANDed against every other filter) so the UI's
 * Module/Action/Actor/Object Type filters can each select multiple values
 * at once, e.g. "ROLE_CREATED or ROLE_DELETED". {@code objectId} stays
 * singular - filtering to more than one specific object id at a time isn't
 * a real use case the UI exposes.
 *
 * Deliberately has no tenantId field: the two endpoints handle tenant
 * scoping completely differently (one always forces the caller's own
 * tenant and never accepts one from the request at all; the other takes
 * an explicit, optional tenantId as its own separate method parameter -
 * see {@code AuditEventService#searchAcrossTenants}) rather than sharing
 * one ambiguous field with two different meanings depending on which
 * endpoint used it.
 */
public record AuditEventSearchCriteria(
        List<String> modules,
        List<String> actions,
        List<UUID> actorIds,
        List<String> objectTypes,
        UUID objectId,
        AuditOutcome outcome,
        AuditSeverity severity,
        OffsetDateTime occurredFrom,
        OffsetDateTime occurredTo,
        /** Lets a reviewer pivot from one event to every other event in the same operation - see the "Part of operation" link on the detail page. */
        UUID correlationId
) {
}
