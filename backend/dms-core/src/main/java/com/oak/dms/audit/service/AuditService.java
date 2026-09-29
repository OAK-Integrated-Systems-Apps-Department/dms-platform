package com.oak.dms.audit.service;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditActionSpec;
import com.oak.dms.audit.entity.AuditEvent;
import com.oak.dms.audit.enums.AuditActorType;
import com.oak.dms.audit.filter.AuditRequestContext;
import com.oak.dms.audit.helper.AuditEventBuilder;
import com.oak.dms.audit.helper.AuditReasonSanitizer;
import com.oak.dms.audit.helper.AuditRecordHasher;
import com.oak.dms.audit.helper.AuditValueRedactor;
import com.oak.dms.audit.helper.SystemActor;
import com.oak.dms.audit.repository.AuditEventRepository;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The single write path for audit_events. Every module that needs to
 * record an audit event goes through {@link #event(String)}, never
 * inserts a row itself - that's what makes the capture rules in
 * docs/audit_events_schema_spec.md's notes section enforceable at all
 * (actor_id's SYSTEM placeholder, reason sanitization/mandatoriness,
 * auth_method's verified-not-submitted rule, correlation_id's
 * server-side-only generation, record_hash's canonical serialization,
 * previous_hash's concurrency safety).
 *
 * Deliberately writes directly to audit_events in the caller's own
 * transaction rather than through the existing OUTBOX_EVENTS mechanism:
 * that table is a proven pattern for "reliably tell another system this
 * happened later, asynchronously" (see OutboxEventPublisher/OutboxRelay),
 * not for "durably store our own source-of-truth row right now" - and its
 * batched, out-of-order relay can't provide the strict write ordering the
 * hash chain below depends on. Calling {@code event(...).log()} from
 * inside an existing {@code @Transactional} business method gets the same
 * atomicity outbox exists to provide (business change and audit row
 * commit together, or neither does) for free, via normal transaction
 * propagation.
 *
 * impersonated_by/impersonated_by_display_name come from
 * {@link TenantContext#getImpersonatorIdOrNull()}/{@link TenantContext#getImpersonatorDisplayNameOrNull()}
 * - null for every ordinary request, and the real admin's id/display name
 * for the duration of a request made under an active "Act as User" session
 * (see {@code ImpersonationContextFilter}, the only thing that ever sets
 * either) - never a value passed in by a call site (see AuditEventBuilder,
 * which has no setter for either on purpose, so a caller can't forge or
 * override this).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditEventRepository auditEventRepository;
    private final AuditRecordHasher hasher;
    private final AuditValueRedactor redactor;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Arbitrary fixed key for the Postgres advisory lock guarding the
     * hash chain's "read the last row, then insert this one" critical
     * section (see previous_hash's concurrency note in the schema spec).
     * Held for the duration of this transaction and released
     * automatically on commit/rollback; must stay unique among any
     * advisory locks this application takes (nothing else currently
     * takes one).
     */
    private static final long CHAIN_LOCK_KEY = 7_331_991_001L;

    public AuditEventBuilder event(String action) {
        return new AuditEventBuilder(this, action);
    }

    @Transactional
    public AuditEvent persist(AuditEventBuilder builder) {
        AuditActionSpec spec = AuditActionCatalog.require(builder.action());

        String sanitizedReason = AuditReasonSanitizer.sanitize(builder.reason());
        if (spec.reasonRequired() && sanitizedReason == null) {
            throw new IllegalArgumentException(
                    "Action '" + builder.action() + "' requires a non-blank reason");
        }

        UUID tenantId = resolveTenantId(builder);
        Actor actor = resolveActor(builder);
        AuditRequestContext requestContext = AuditRequestContext.currentOrNull();
        UUID requestId = requestContext != null ? requestContext.getRequestId() : UUID.randomUUID();
        UUID correlationId = requestContext != null ? requestContext.getCorrelationId() : requestId;

        AuditEvent draft = AuditEvent.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .actorId(actor.id())
                .actorType(actor.type())
                .actorDisplayName(actor.displayName())
                .impersonatedBy(TenantContext.getImpersonatorIdOrNull())
                .impersonatedByDisplayName(TenantContext.getImpersonatorDisplayNameOrNull())
                .action(builder.action())
                .module(spec.module())
                .objectType(builder.objectType())
                .objectId(builder.objectId())
                .resourceName(builder.resourceName())
                .objectVersion(builder.objectVersion())
                .outcome(builder.outcome())
                .failureReason(builder.failureReason())
                .occurredAt(OffsetDateTime.now())
                .previousValue(redactor.redactAndSerialize(builder.previousValue()))
                .newValue(redactor.redactAndSerialize(builder.newValue()))
                .reason(sanitizedReason)
                .sessionId(requestContext != null ? requestContext.getSessionId() : null)
                .authMethod(builder.authMethod())
                .permissionCode(builder.permissionCode())
                .dataClassification(builder.dataClassification())
                .legalHoldFlag(builder.legalHoldFlag())
                .ipAddress(requestContext != null ? requestContext.getIpAddress() : null)
                .userAgent(requestContext != null ? requestContext.getUserAgent() : null)
                .clientType(requestContext != null ? requestContext.getClientType() : null)
                .correlationId(correlationId)
                .requestId(requestId)
                .severity(spec.severity())
                .build();

        // Everything from here down must happen while holding the chain
        // lock: two concurrent app instances must never both read the
        // same tail row as "previous" for two different new rows.
        jdbcTemplate.execute("SELECT pg_advisory_xact_lock(" + CHAIN_LOCK_KEY + ")");

        String previousHash = auditEventRepository.findTopByOrderByOccurredAtDescIdDesc()
                .map(AuditEvent::getRecordHash)
                .orElse(null);

        AuditEvent chained = draft.toBuilder().previousHash(previousHash).build();
        AuditEvent finalized = chained.toBuilder().recordHash(hasher.hash(chained)).build();

        AuditEvent saved = auditEventRepository.save(finalized);
        log.debug("Recorded audit event {} ({}) for tenant {}", saved.getAction(), saved.getId(), saved.getTenantId());
        return saved;
    }

    private UUID resolveTenantId(AuditEventBuilder builder) {
        if (builder.isPlatformLevel()) {
            return null;
        }
        if (builder.tenantId() != null) {
            return builder.tenantId();
        }
        try {
            return TenantContext.getTenantId();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private Actor resolveActor(AuditEventBuilder builder) {
        if (builder.actorId() != null) {
            return new Actor(builder.actorId(), builder.actorType(), builder.actorDisplayName());
        }
        try {
            UUID userId = TenantContext.getUserId();
            return new Actor(userId, AuditActorType.USER, TenantContext.getDisplayNameOrNull());
        } catch (IllegalStateException e) {
            return new Actor(SystemActor.SYSTEM_USER_ID, AuditActorType.SYSTEM, SystemActor.SYSTEM_DISPLAY_NAME);
        }
    }

    private record Actor(UUID id, AuditActorType type, String displayName) {
    }
}
