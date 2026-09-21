package com.oak.dms.audit.helper;

import com.oak.dms.audit.entity.AuditEvent;
import com.oak.dms.audit.enums.AuditActorType;
import com.oak.dms.audit.enums.AuditAuthMethod;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;

import java.util.UUID;

/**
 * Fluent entry point for recording one audit event. Obtained from
 * {@link AuditService#event(String)}, finished with {@link #log()}.
 * Every capture rule from docs/audit_events_schema_spec.md's notes
 * section (actor resolution, reason sanitization/mandatoriness,
 * server-side-only correlation/request id, canonical hashing, the
 * concurrency-safe hash chain) is enforced centrally by
 * {@link AuditService#persist}, not here - this class is just a plain
 * data holder with a fluent API.
 *
 * <pre>{@code
 * auditService.event("ROLE_DELETED")
 *         .objectType("ROLE").objectId(role.getRoleId()).resourceName(role.getRoleName())
 *         .outcome(AuditOutcome.SUCCESS)
 *         .reason(request.getReason())
 *         .log();
 * }</pre>
 */
public final class AuditEventBuilder {

    private final AuditService auditService;
    private final String action;

    private UUID tenantId;
    private boolean platformLevel;
    private UUID actorId;
    private AuditActorType actorType;
    private String actorDisplayName;
    private String objectType;
    private UUID objectId;
    private String resourceName;
    private Integer objectVersion;
    private AuditOutcome outcome;
    private String failureReason;
    private Object previousValue;
    private Object newValue;
    private String reason;
    private AuditAuthMethod authMethod;
    private String permissionCode;
    private String dataClassification;
    private Boolean legalHoldFlag;

    public AuditEventBuilder(AuditService auditService, String action) {
        this.auditService = auditService;
        this.action = action;
    }

    /** Overrides the tenant otherwise defaulted from TenantContext. Rarely needed outside a background job. */
    public AuditEventBuilder tenantId(UUID tenantId) {
        this.tenantId = tenantId;
        return this;
    }

    /**
     * Marks this event as having no single owning tenant - forces
     * tenant_id to null even if TenantContext is bound, since defaulting
     * to the acting admin's own tenant would be wrong for an event that
     * isn't about that tenant. Used by {@code TenantServiceImpl}/
     * {@code TenantProvisioningService}'s tenant-lifecycle events
     * (TENANT_CREATED/PROVISIONED/UPDATED/DEACTIVATED) - a PLATFORM_ADMIN
     * creating or changing a tenant isn't acting "as" any one tenant, so
     * there's no correct non-null value to default to. Every other action
     * wired up so far - roles, permissions, users, auth, the outbox relay -
     * genuinely belongs to one tenant or falls back to the SYSTEM actor
     * with tenant_id left unset, so this stays specific to platform-level
     * resources rather than becoming a general-purpose escape hatch.
     * {@link PlatformAuditEventController} already depends on
     * platform-level rows (tenant_id IS NULL) being a real, queryable
     * case.
     */
    public AuditEventBuilder platformLevel() {
        this.platformLevel = true;
        return this;
    }

    /** Overrides the actor otherwise defaulted to the current TenantContext user. */
    public AuditEventBuilder actor(UUID actorId, String displayName) {
        this.actorId = actorId;
        this.actorType = AuditActorType.USER;
        this.actorDisplayName = displayName;
        return this;
    }

    /** For events triggered by a scheduled job or other non-request code path, not a human user. */
    public AuditEventBuilder systemActor() {
        this.actorId = SystemActor.SYSTEM_USER_ID;
        this.actorType = AuditActorType.SYSTEM;
        this.actorDisplayName = SystemActor.SYSTEM_DISPLAY_NAME;
        return this;
    }

    public AuditEventBuilder objectType(String objectType) {
        this.objectType = objectType;
        return this;
    }

    public AuditEventBuilder objectId(UUID objectId) {
        this.objectId = objectId;
        return this;
    }

    public AuditEventBuilder resourceName(String resourceName) {
        this.resourceName = resourceName;
        return this;
    }

    public AuditEventBuilder objectVersion(Integer objectVersion) {
        this.objectVersion = objectVersion;
        return this;
    }

    public AuditEventBuilder outcome(AuditOutcome outcome) {
        this.outcome = outcome;
        return this;
    }

    public AuditEventBuilder failureReason(String failureReason) {
        this.failureReason = failureReason;
        return this;
    }

    /** Redacted for sensitive fields and JSON-serialized by AuditService - pass a plain object/map, not a JSON string. */
    public AuditEventBuilder previousValue(Object previousValue) {
        this.previousValue = previousValue;
        return this;
    }

    /** Redacted for sensitive fields and JSON-serialized by AuditService - pass a plain object/map, not a JSON string. */
    public AuditEventBuilder newValue(Object newValue) {
        this.newValue = newValue;
        return this;
    }

    /** Sanitized and length-limited by AuditService; whether it's required is decided per action by AuditActionCatalog. */
    public AuditEventBuilder reason(String reason) {
        this.reason = reason;
        return this;
    }

    /**
     * Only ever call this with the factor that was actually verified and
     * succeeded - never one merely submitted/attempted. Intended for
     * AuthService's own success paths only.
     */
    public AuditEventBuilder authMethod(AuditAuthMethod authMethod) {
        this.authMethod = authMethod;
        return this;
    }

    public AuditEventBuilder permissionCode(String permissionCode) {
        this.permissionCode = permissionCode;
        return this;
    }

    public AuditEventBuilder dataClassification(String dataClassification) {
        this.dataClassification = dataClassification;
        return this;
    }

    public AuditEventBuilder legalHoldFlag(Boolean legalHoldFlag) {
        this.legalHoldFlag = legalHoldFlag;
        return this;
    }

    /** Sanitizes, resolves defaults, computes the hash chain, and persists the row. */
    public AuditEvent log() {
        return auditService.persist(this);
    }

    public String action() {
        return action;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public boolean isPlatformLevel() {
        return platformLevel;
    }

    public UUID actorId() {
        return actorId;
    }

    public AuditActorType actorType() {
        return actorType;
    }

    public String actorDisplayName() {
        return actorDisplayName;
    }

    public String objectType() {
        return objectType;
    }

    public UUID objectId() {
        return objectId;
    }

    public String resourceName() {
        return resourceName;
    }

    public Integer objectVersion() {
        return objectVersion;
    }

    public AuditOutcome outcome() {
        return outcome;
    }

    public String failureReason() {
        return failureReason;
    }

    public Object previousValue() {
        return previousValue;
    }

    public Object newValue() {
        return newValue;
    }

    public String reason() {
        return reason;
    }

    public AuditAuthMethod authMethod() {
        return authMethod;
    }

    public String permissionCode() {
        return permissionCode;
    }

    public String dataClassification() {
        return dataClassification;
    }

    public Boolean legalHoldFlag() {
        return legalHoldFlag;
    }
}
