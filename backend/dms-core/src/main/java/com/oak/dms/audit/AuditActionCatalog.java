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

    private static final Map<String, AuditActionSpec> ACTIONS = Map.ofEntries(
            // Roles (RoleController) - module ASM
            Map.entry("ROLE_CREATED", new AuditActionSpec("ASM", AuditSeverity.MEDIUM, false)),
            Map.entry("ROLE_UPDATED", new AuditActionSpec("ASM", AuditSeverity.MEDIUM, false)),
            Map.entry("ROLE_DELETED", new AuditActionSpec("ASM", AuditSeverity.HIGH, false)),

            // Role-permission assignment (RolePermissionController) - module ASM
            Map.entry("ROLE_PERMISSION_ASSIGNED", new AuditActionSpec("ASM", AuditSeverity.MEDIUM, false)),
            Map.entry("ROLE_PERMISSION_REVOKED", new AuditActionSpec("ASM", AuditSeverity.HIGH, false)),

            // Permissions (PermissionController) - module ASM
            Map.entry("PERMISSION_CREATED", new AuditActionSpec("ASM", AuditSeverity.MEDIUM, false)),
            Map.entry("PERMISSION_UPDATED", new AuditActionSpec("ASM", AuditSeverity.MEDIUM, false)),
            Map.entry("PERMISSION_DELETED", new AuditActionSpec("ASM", AuditSeverity.HIGH, false)),

            // Auth (AuthService) - module ASM
            Map.entry("LOGIN_SUCCESS", new AuditActionSpec("ASM", AuditSeverity.LOW, false)),
            Map.entry("LOGIN_FAILURE", new AuditActionSpec("ASM", AuditSeverity.MEDIUM, false)),
            Map.entry("LOGOUT", new AuditActionSpec("ASM", AuditSeverity.LOW, false)),
            // No TOKEN_REFRESH entry - see AuthService#refresh's doc comment
            // for why silent session renewal is deliberately not audited.
            Map.entry("PASSWORD_RESET_REQUESTED", new AuditActionSpec("ASM", AuditSeverity.LOW, false)),
            Map.entry("PASSWORD_RESET_COMPLETED", new AuditActionSpec("ASM", AuditSeverity.MEDIUM, false)),

            // Users (UserController) - module OTUA
            Map.entry("USER_CREATED", new AuditActionSpec("OTUA", AuditSeverity.MEDIUM, false)),
            Map.entry("USER_UPDATED", new AuditActionSpec("OTUA", AuditSeverity.LOW, false)),
            Map.entry("USER_ROLE_ASSIGNED", new AuditActionSpec("OTUA", AuditSeverity.MEDIUM, false)),
            Map.entry("USER_DEACTIVATED", new AuditActionSpec("OTUA", AuditSeverity.HIGH, true)),
            Map.entry("USER_REACTIVATED", new AuditActionSpec("OTUA", AuditSeverity.MEDIUM, false)),
            Map.entry("USER_DELETED", new AuditActionSpec("OTUA", AuditSeverity.HIGH, false)),

            // Audit trail export (AuditEventController#exportCsv) - module ABDR
            Map.entry("AUDIT_LOG_EXPORTED", new AuditActionSpec("ABDR", AuditSeverity.MEDIUM, false)),

            // Background infrastructure (OutboxRelay) - module SHAD (System
            // Health and Administration Dashboard - the module whose whole
            // purpose is platform infrastructure status, not a business
            // domain). Only the terminal, unrecoverable failure is logged
            // here - see OutboxRelay's own doc comment for why every
            // successful publish and every merely-transient retry
            // deliberately are NOT audited (that would flood the trail with
            // routine, non-actionable noise).
            Map.entry("OUTBOX_EVENT_PUBLISH_FAILED", new AuditActionSpec("SHAD", AuditSeverity.HIGH, false))
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
