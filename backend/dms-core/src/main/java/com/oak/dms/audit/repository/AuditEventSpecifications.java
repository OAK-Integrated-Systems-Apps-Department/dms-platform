package com.oak.dms.audit.repository;

import com.oak.dms.audit.entity.AuditEvent;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.enums.AuditSeverity;
import org.springframework.data.jpa.domain.Specification;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One {@link Specification} per optional search filter. Every method
 * always returns a real (never null) Specification - an absent filter
 * resolves to {@code cb.conjunction()} (an unconditional "true") rather
 * than null, so these can always be chained with plain {@code .and(...)}
 * regardless of which filters the caller actually supplied, with no
 * null-handling burden on the caller.
 */
public final class AuditEventSpecifications {

    private AuditEventSpecifications() {
    }

    /**
     * Null means "no tenant restriction at all" (matches every row,
     * including platform-level ones where tenant_id itself is null) -
     * used for a PLATFORM_ADMIN caller with no explicit tenantId filter.
     * Every other caller always passes their own, real tenant id here.
     */
    public static Specification<AuditEvent> tenantId(UUID tenantId) {
        return (root, query, cb) -> tenantId == null ? cb.conjunction() : cb.equal(root.get("tenantId"), tenantId);
    }

    /** Matches any one of `modules` (an OR within this field) - null/empty means no constraint. */
    public static Specification<AuditEvent> modules(List<String> modules) {
        return (root, query, cb) -> (modules == null || modules.isEmpty()) ? cb.conjunction() : root.get("module").in(modules);
    }

    /** Matches any one of `actions` - null/empty means no constraint. */
    public static Specification<AuditEvent> actions(List<String> actions) {
        return (root, query, cb) -> (actions == null || actions.isEmpty()) ? cb.conjunction() : root.get("action").in(actions);
    }

    /** Matches any one of `actorIds` - null/empty means no constraint. */
    public static Specification<AuditEvent> actorIds(List<UUID> actorIds) {
        return (root, query, cb) -> (actorIds == null || actorIds.isEmpty()) ? cb.conjunction() : root.get("actorId").in(actorIds);
    }

    /** Matches any one of `objectTypes` - null/empty means no constraint. */
    public static Specification<AuditEvent> objectTypes(List<String> objectTypes) {
        return (root, query, cb) -> (objectTypes == null || objectTypes.isEmpty()) ? cb.conjunction() : root.get("objectType").in(objectTypes);
    }

    public static Specification<AuditEvent> objectId(UUID objectId) {
        return (root, query, cb) -> objectId == null ? cb.conjunction() : cb.equal(root.get("objectId"), objectId);
    }

    public static Specification<AuditEvent> correlationId(UUID correlationId) {
        return (root, query, cb) -> correlationId == null ? cb.conjunction() : cb.equal(root.get("correlationId"), correlationId);
    }

    public static Specification<AuditEvent> outcome(AuditOutcome outcome) {
        return (root, query, cb) -> outcome == null ? cb.conjunction() : cb.equal(root.get("outcome"), outcome);
    }

    public static Specification<AuditEvent> severity(AuditSeverity severity) {
        return (root, query, cb) -> severity == null ? cb.conjunction() : cb.equal(root.get("severity"), severity);
    }

    /** Both bounds inclusive; either or both may be null (an open-ended range). */
    public static Specification<AuditEvent> occurredBetween(OffsetDateTime from, OffsetDateTime to) {
        return (root, query, cb) -> {
            if (from != null && to != null) {
                return cb.between(root.get("occurredAt"), from, to);
            }
            if (from != null) {
                return cb.greaterThanOrEqualTo(root.get("occurredAt"), from);
            }
            if (to != null) {
                return cb.lessThanOrEqualTo(root.get("occurredAt"), to);
            }
            return cb.conjunction();
        };
    }
}
