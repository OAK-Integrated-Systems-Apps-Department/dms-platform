package com.oak.dms.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One immutable row in the platform's audit trail. Written exactly once,
 * only via {@link AuditService} - never updated or deleted (see
 * V23__create_audit_events.sql, which revokes UPDATE/DELETE at the DB
 * level, and {@link AuditEventRepository}, which exposes no method that
 * could do either). A correction to a past event is always a new
 * compensating row, never an edit to this one.
 *
 * No {@code @Setter}: every field is fixed at construction via the
 * {@link Builder}, matching the table's own immutability.
 *
 * Implements {@link Persistable} with {@code isNew()} hardcoded to
 * {@code true}: {@code id} is assigned client-side (a random UUID, before
 * this entity is built at all - see {@link AuditService#persist}, which
 * needs it to compute record_hash before insert), not left null for
 * Hibernate's {@code @UuidGenerator} to fill in at flush time. Without
 * this, Spring Data's default "is this new?" check sees a non-null
 * {@code @Id} and assumes the entity already exists, routing
 * {@code repository.save()} through {@code entityManager.merge()} instead
 * of {@code persist()} - which fails outright for a row that was never
 * actually inserted (a real bug hit while testing this feature end to
 * end: every audited action 500'd with
 * {@code org.hibernate.StaleObjectStateException}). Correct here
 * specifically because every AuditEvent instance genuinely is new - there
 * is no code path anywhere that loads an existing row and calls save() on
 * it again.
 */
@Entity
@Table(name = "audit_events")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class AuditEvent implements Persistable<UUID> {

    @Override
    public boolean isNew() {
        return true;
    }

    // Plain assigned identifier - no @UuidGenerator. id is always set by
    // application code (AuditService#persist needs it before insert, to
    // compute record_hash) before an AuditEvent is ever built, never left
    // for Hibernate to fill in. A generator annotation here actively
    // breaks persist(): Hibernate's own "is this new or detached" check
    // (independent of, and in addition to, Persistable.isNew() above,
    // which only governs Spring Data's merge-vs-persist choice) treats a
    // pre-populated id under a non-"assigned" generator as evidence the
    // entity must be detached, throwing "Detached entity passed to
    // persist" - a second real bug hit chasing the first one during
    // end-to-end testing.
    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Null for platform-level events with no single owning tenant. */
    @Column(name = "tenant_id", updatable = false)
    private UUID tenantId;

    /**
     * Never null in practice: {@link SystemActor#SYSTEM_USER_ID} is used
     * for system-triggered events instead of leaving this null. Not a
     * live FK - see the migration comment for why.
     */
    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", updatable = false, nullable = false)
    private AuditActorType actorType;

    @Column(name = "actor_display_name", updatable = false)
    private String actorDisplayName;

    /**
     * The real admin's id, only populated for an event generated during an
     * active "Act as User" impersonation session (see ImpersonationService/
     * ImpersonationContextFilter) - null for every ordinary event. Sourced
     * exclusively from AuditService reading TenantContext's own
     * impersonator field, never from a manually-supplied value at a call
     * site (AuditEventBuilder has no setter for this on purpose).
     */
    @Column(name = "impersonated_by", updatable = false)
    private UUID impersonatedBy;

    /** Point-in-time snapshot of the real admin's display name, same reasoning as {@link #actorDisplayName} - a raw id alone isn't enough to read the record without a live join back to a users row that may since have been renamed or deleted. Null exactly when {@link #impersonatedBy} is null. */
    @Column(name = "impersonated_by_display_name", updatable = false)
    private String impersonatedByDisplayName;

    @Column(name = "action", updatable = false, nullable = false)
    private String action;

    @Column(name = "module", updatable = false, nullable = false)
    private String module;

    @Column(name = "object_type", updatable = false, nullable = false)
    private String objectType;

    @Column(name = "object_id", updatable = false)
    private UUID objectId;

    @Column(name = "resource_name", updatable = false)
    private String resourceName;

    @Column(name = "object_version", updatable = false)
    private Integer objectVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", updatable = false, nullable = false)
    private AuditOutcome outcome;

    @Column(name = "failure_reason", updatable = false)
    private String failureReason;

    @Column(name = "occurred_at", updatable = false, nullable = false)
    private OffsetDateTime occurredAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "previous_value", updatable = false, columnDefinition = "jsonb")
    private String previousValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_value", updatable = false, columnDefinition = "jsonb")
    private String newValue;

    /** Length-limited and sanitized before this entity is ever built - see AuditEventBuilder. */
    @Column(name = "reason", updatable = false)
    private String reason;

    /**
     * Keycloak's own opaque session identifier (the JWT's {@code sid}/
     * {@code session_state} claim) - stored verbatim as a string, not a
     * UUID: Keycloak generates these as arbitrary opaque strings (e.g.
     * {@code "NhFcDsECmgpa7Nk0VILq29SY"}), never actual UUIDs, so a UUID
     * column here made this field silently null on every single row - see
     * AuditRequestContextFilter#resolveSessionId, which used to
     * UUID.fromString() this value and swallow the resulting exception.
     */
    @Column(name = "session_id", updatable = false)
    private String sessionId;

    /** The factor actually verified and succeeded - never merely submitted. */
    @Enumerated(EnumType.STRING)
    @Column(name = "auth_method", updatable = false)
    private AuditAuthMethod authMethod;

    /** Plain string snapshot - deliberately not a live FK to permissions.permission_code. */
    @Column(name = "permission_code", updatable = false)
    private String permissionCode;

    @Column(name = "data_classification", updatable = false)
    private String dataClassification;

    @Column(name = "legal_hold_flag", updatable = false)
    private Boolean legalHoldFlag;

    @Column(name = "ip_address", updatable = false)
    private String ipAddress;

    @Column(name = "user_agent", updatable = false)
    private String userAgent;

    @Enumerated(EnumType.STRING)
    @Column(name = "client_type", updatable = false)
    private AuditClientType clientType;

    /**
     * Always server-resolved, never a raw client-supplied value - a caller
     * can only ever continue a correlation the server itself signed and
     * issued to it (see {@link AuditCorrelationTokenCodec}), never invent
     * an arbitrary one. Defaults to this row's own request_id when no
     * continuation was presented or verified.
     */
    @Column(name = "correlation_id", updatable = false)
    private UUID correlationId;

    @Column(name = "request_id", updatable = false)
    private UUID requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", updatable = false)
    private AuditSeverity severity;

    /** SHA-256 hex digest of this row's own content - see AuditRecordHasher for the canonical field order. */
    @Column(name = "record_hash", updatable = false, nullable = false)
    private String recordHash;

    /** Hash of the immediately preceding row; null only for the very first row ever written. */
    @Column(name = "previous_hash", updatable = false)
    private String previousHash;
}
