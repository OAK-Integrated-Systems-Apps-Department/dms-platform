package com.oak.dms.audit;

import java.util.Map;

/**
 * The event catalogue referenced throughout
 * docs/audit_events_schema_spec.md: the single place that decides an
 * action's module, severity, and whether a reason is mandatory for it.
 * AuditService looks up every action here rather than accepting those
 * three things as caller-supplied parameters, so severity and
 * reason-mandatoriness can never drift per screen/controller.
 *
 * Module codes match permission_modules.module_code exactly (see
 * V7__seed_permission_modules_and_actions.sql):
 *  - ASM  = Access and Security Management (roles, permissions, auth policy, security events)
 *  - OTUA = Organization, Tenant and User Administration (user administration)
 *  - ABDR = Comprehensive Audit Management (the audit trail itself, e.g. exporting it)
 *  - SHAD = System Health and Administration Dashboard (platform infrastructure status, e.g. the outbox relay)
 *
 * reasonRequired would, per the spec's own example, be true for
 * destructive/access-revoking actions ("mandatory for legal hold /
 * permanent deletion"). USER_DEACTIVATED is the first one wired up this
 * way - UserController#deactivateUser takes a DeactivateUserRequest with a
 * mandatory reason field, sourced from a required text input in the
 * Activate/Deactivate confirmation dialog (see UserStatusDialog on the
 * frontend). Every other delete/revoke/deactivate endpoint below is still
 * false, because none of RoleController/RolePermissionController/
 * PermissionController's equivalent endpoints accept a reason field from
 * the caller yet (confirmed by inspection - no DTO on any of those
 * endpoints has one). Marking one of those true without a request field to
 * source it from would make every such call fail every time. Flip an entry
 * to true only once its endpoint's request DTO actually carries a reason -
 * AuditEventBuilder.reason(...) already supports passing one through
 * whenever it does.
 *
 * New actions must be added here before AuditService will accept them -
 * see {@link #require}.
 */
public final class AuditActionCatalog {

    // Action-code constants - every auditService.event(...) call site across
    // the codebase should reference one of these instead of retyping the
    // literal, so a typo becomes a compile error instead of a runtime
    // IllegalStateException from require() below.
    public static final String ROLE_CREATED = "ROLE_CREATED";
    public static final String ROLE_UPDATED = "ROLE_UPDATED";
    public static final String ROLE_DELETED = "ROLE_DELETED";
    public static final String ROLE_PERMISSION_ASSIGNED = "ROLE_PERMISSION_ASSIGNED";
    public static final String ROLE_PERMISSION_REVOKED = "ROLE_PERMISSION_REVOKED";
    public static final String PERMISSION_CREATED = "PERMISSION_CREATED";
    public static final String PERMISSION_UPDATED = "PERMISSION_UPDATED";
    public static final String PERMISSION_DELETED = "PERMISSION_DELETED";
    public static final String LOGIN_SUCCESS = "LOGIN_SUCCESS";
    public static final String LOGIN_FAILURE = "LOGIN_FAILURE";
    public static final String LOGOUT = "LOGOUT";
    public static final String PASSWORD_RESET_REQUESTED = "PASSWORD_RESET_REQUESTED";
    public static final String PASSWORD_RESET_COMPLETED = "PASSWORD_RESET_COMPLETED";
    public static final String USER_CREATED = "USER_CREATED";
    public static final String USER_UPDATED = "USER_UPDATED";
    public static final String USER_ROLE_ASSIGNED = "USER_ROLE_ASSIGNED";
    public static final String USER_DEACTIVATED = "USER_DEACTIVATED";
    public static final String USER_REACTIVATED = "USER_REACTIVATED";
    public static final String USER_DELETED = "USER_DELETED";
    public static final String AUDIT_LOG_EXPORTED = "AUDIT_LOG_EXPORTED";
    public static final String OUTBOX_EVENT_PUBLISH_FAILED = "OUTBOX_EVENT_PUBLISH_FAILED";
    public static final String USER_ROLE_REVOKED = "USER_ROLE_REVOKED";
    public static final String PERMISSION_ACTION_CREATED = "PERMISSION_ACTION_CREATED";
    public static final String PERMISSION_ACTION_UPDATED = "PERMISSION_ACTION_UPDATED";
    public static final String PERMISSION_ACTION_DELETED = "PERMISSION_ACTION_DELETED";
    public static final String PERMISSION_MODULE_CREATED = "PERMISSION_MODULE_CREATED";
    public static final String PERMISSION_MODULE_UPDATED = "PERMISSION_MODULE_UPDATED";
    public static final String PERMISSION_MODULE_DELETED = "PERMISSION_MODULE_DELETED";
    public static final String TENANT_CREATED = "TENANT_CREATED";
    public static final String TENANT_PROVISIONED = "TENANT_PROVISIONED";
    public static final String TENANT_UPDATED = "TENANT_UPDATED";
    public static final String TENANT_DEACTIVATED = "TENANT_DEACTIVATED";
    public static final String ACCESS_DENIED = "ACCESS_DENIED";
    public static final String IMPERSONATION_STARTED = "IMPERSONATION_STARTED";
    public static final String IMPERSONATION_ENDED = "IMPERSONATION_ENDED";

    private static final Map<String, AuditActionSpec> ACTIONS = Map.ofEntries(
            // Roles (RoleController) - module ASM
            Map.entry(ROLE_CREATED, new AuditActionSpec("ASM", AuditSeverity.MEDIUM, false)),
            Map.entry(ROLE_UPDATED, new AuditActionSpec("ASM", AuditSeverity.MEDIUM, false)),
            Map.entry(ROLE_DELETED, new AuditActionSpec("ASM", AuditSeverity.HIGH, false)),

            // Role-permission assignment (RolePermissionController) - module ASM
            Map.entry(ROLE_PERMISSION_ASSIGNED, new AuditActionSpec("ASM", AuditSeverity.MEDIUM, false)),
            Map.entry(ROLE_PERMISSION_REVOKED, new AuditActionSpec("ASM", AuditSeverity.HIGH, false)),

            // Permissions (PermissionController) - module ASM
            Map.entry(PERMISSION_CREATED, new AuditActionSpec("ASM", AuditSeverity.MEDIUM, false)),
            Map.entry(PERMISSION_UPDATED, new AuditActionSpec("ASM", AuditSeverity.MEDIUM, false)),
            Map.entry(PERMISSION_DELETED, new AuditActionSpec("ASM", AuditSeverity.HIGH, false)),

            // Auth (AuthService) - module ASM
            Map.entry(LOGIN_SUCCESS, new AuditActionSpec("ASM", AuditSeverity.LOW, false)),
            Map.entry(LOGIN_FAILURE, new AuditActionSpec("ASM", AuditSeverity.MEDIUM, false)),
            Map.entry(LOGOUT, new AuditActionSpec("ASM", AuditSeverity.LOW, false)),
            // No TOKEN_REFRESH entry - see AuthService#refresh's doc comment
            // for why silent session renewal is deliberately not audited.
            Map.entry(PASSWORD_RESET_REQUESTED, new AuditActionSpec("ASM", AuditSeverity.LOW, false)),
            Map.entry(PASSWORD_RESET_COMPLETED, new AuditActionSpec("ASM", AuditSeverity.MEDIUM, false)),
            // Impersonation (ImpersonationService) - module ASM: starting a
            // session under someone else's identity is at least as
            // sensitive as a role deletion (HIGH); ending one is routine
            // and expected, so MEDIUM matches USER_REACTIVATED's tier.
            Map.entry(IMPERSONATION_STARTED, new AuditActionSpec("ASM", AuditSeverity.HIGH, false)),
            Map.entry(IMPERSONATION_ENDED, new AuditActionSpec("ASM", AuditSeverity.MEDIUM, false)),

            // Users (UserController) - module OTUA
            Map.entry(USER_CREATED, new AuditActionSpec("OTUA", AuditSeverity.MEDIUM, false)),
            Map.entry(USER_UPDATED, new AuditActionSpec("OTUA", AuditSeverity.LOW, false)),
            Map.entry(USER_ROLE_ASSIGNED, new AuditActionSpec("OTUA", AuditSeverity.MEDIUM, false)),
            Map.entry(USER_ROLE_REVOKED, new AuditActionSpec("OTUA", AuditSeverity.MEDIUM, false)),
            Map.entry(USER_DEACTIVATED, new AuditActionSpec("OTUA", AuditSeverity.HIGH, true)),
            Map.entry(USER_REACTIVATED, new AuditActionSpec("OTUA", AuditSeverity.MEDIUM, false)),
            Map.entry(USER_DELETED, new AuditActionSpec("OTUA", AuditSeverity.HIGH, false)),

            // Permission actions (PermissionActionController) - module ASM,
            // same severities as the Permissions group above (this is one
            // level up the catalog hierarchy: Module -> Action -> Permission).
            Map.entry(PERMISSION_ACTION_CREATED, new AuditActionSpec("ASM", AuditSeverity.MEDIUM, false)),
            Map.entry(PERMISSION_ACTION_UPDATED, new AuditActionSpec("ASM", AuditSeverity.MEDIUM, false)),
            Map.entry(PERMISSION_ACTION_DELETED, new AuditActionSpec("ASM", AuditSeverity.HIGH, false)),

            // Permission modules (PermissionModuleController) - module ASM,
            // top of the same hierarchy.
            Map.entry(PERMISSION_MODULE_CREATED, new AuditActionSpec("ASM", AuditSeverity.MEDIUM, false)),
            Map.entry(PERMISSION_MODULE_UPDATED, new AuditActionSpec("ASM", AuditSeverity.MEDIUM, false)),
            Map.entry(PERMISSION_MODULE_DELETED, new AuditActionSpec("ASM", AuditSeverity.HIGH, false)),

            // Tenants (TenantController) - module OTUA (the permission
            // catalog's own "Manage Tenants" action group already lives
            // under OTUA, alongside user administration - see
            // V10__seed_fine_grained_permissions.sql). TENANT_PROVISIONED
            // is HIGH, not MEDIUM like a plain create - it also creates
            // that tenant's first TENANT_ADMIN account in the same call
            // (see TenantProvisioningService), so it's a privilege grant as
            // much as a resource creation. TENANT_DEACTIVATED has no reason
            // field to source reasonRequired=true from - DeleteMapping
            // takes no request body - same constraint documented above for
            // every other delete/deactivate entry in this catalog.
            Map.entry(TENANT_CREATED, new AuditActionSpec("OTUA", AuditSeverity.MEDIUM, false)),
            Map.entry(TENANT_PROVISIONED, new AuditActionSpec("OTUA", AuditSeverity.HIGH, false)),
            Map.entry(TENANT_UPDATED, new AuditActionSpec("OTUA", AuditSeverity.MEDIUM, false)),
            Map.entry(TENANT_DEACTIVATED, new AuditActionSpec("OTUA", AuditSeverity.HIGH, false)),

            // Every @PreAuthorize denial across the whole app, captured
            // centrally in GlobalExceptionHandler rather than per
            // endpoint - see that class's own comment. Module ASM (its
            // own doc comment above already scopes "security events" as
            // in-module). MEDIUM, not HIGH: a single denial is routinely
            // just a caller hitting a screen/action their role doesn't
            // grant (normal UI use, not inherently hostile) - severity
            // here is about "worth a human's attention," not "who was
            // right," so it's calibrated the same as LOGIN_FAILURE rather
            // than a destructive-action HIGH. No reason field exists at
            // this generic a layer, so reasonRequired stays false, same
            // constraint as every other entry here.
            Map.entry(ACCESS_DENIED, new AuditActionSpec("ASM", AuditSeverity.MEDIUM, false)),

            // Audit trail export (AuditEventController#exportCsv) - module ABDR
            Map.entry(AUDIT_LOG_EXPORTED, new AuditActionSpec("ABDR", AuditSeverity.MEDIUM, false)),

            // Background infrastructure (OutboxRelay) - module SHAD (System
            // Health and Administration Dashboard - the module whose whole
            // purpose is platform infrastructure status, not a business
            // domain). Only the terminal, unrecoverable failure is logged
            // here - see OutboxRelay's own doc comment for why every
            // successful publish and every merely-transient retry
            // deliberately are NOT audited (that would flood the trail with
            // routine, non-actionable noise).
            Map.entry(OUTBOX_EVENT_PUBLISH_FAILED, new AuditActionSpec("SHAD", AuditSeverity.HIGH, false))
    );

    private AuditActionCatalog() {
    }

    public static AuditActionSpec require(String action) {
        AuditActionSpec spec = ACTIONS.get(action);
        if (spec == null) {
            throw new IllegalStateException(
                    "Action '" + action + "' is not registered in AuditActionCatalog - add it there "
                            + "(module, severity, reason-required) rather than passing those ad hoc.");
        }
        return spec;
    }
}
