package com.oak.dms.security.rbac;

/**
 * Single source of truth for the granular permission codes checked by
 * {@code @authz.hasPermission(...)} / {@code @authz.hasAnyPermission(...)}
 * across {@code UserController}, {@code UserRoleController},
 * {@code RoleController} and {@code RolePermissionController}. Each value
 * here must exactly match a {@code permission_code} row seeded by
 * {@code V10__seed_fine_grained_permissions.sql} - see
 * {@code V15__deprecate_legacy_permissions_and_backfill_granular.sql} for
 * how the 4 legacy coarse codes these replaced (user.view/user.manage/
 * role.view/role.manage) were deprecated and backfilled so no existing
 * role lost access when the checks below switched over.
 * <p>
 * {@code @PreAuthorize} is evaluated as Spring EL, which has no notion of a
 * Java import, so a constant here must be referenced through a
 * fully-qualified {@code T(...)} type reference rather than the plain
 * field name a normal Java caller would use:
 * <pre>{@code
 * @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.PermissionCodes).OTUA_USERS_CREATE)")
 * }</pre>
 * Not every permission in the catalog has a constant here - only the ones
 * actually referenced by an {@code @PreAuthorize} check. {@code UserController#purgeUser}
 * is deliberately excluded from this catalog altogether (gated on
 * {@code @authz.isTenantAdmin()} instead) - see that method's own doc
 * comment for why.
 */
public final class PermissionCodes {

    private PermissionCodes() {
    }

    // Users - OTUA (Organization, Tenant and User Administration) / Manage Users
    public static final String OTUA_USERS_CREATE = "otua.manage-users.create";
    public static final String OTUA_USERS_UPDATE = "otua.manage-users.update";
    public static final String OTUA_USERS_VIEW = "otua.manage-users.view";
    public static final String OTUA_USERS_RESET_PASSWORD = "otua.manage-users.reset-password";
    public static final String OTUA_USERS_ACTIVATE_DEACTIVATE = "otua.manage-users.activate-deactivate";
    public static final String OTUA_USERS_ASSIGN_ROLE = "otua.manage-users.assign-role";

    // Roles - ASM (Access and Security Management) / Manage Roles & Permissions
    public static final String ASM_ROLES_CREATE_ROLE = "asm.manage-roles-permissions.create-role";
    public static final String ASM_ROLES_UPDATE_ROLE = "asm.manage-roles-permissions.update-role";
    public static final String ASM_ROLES_DELETE_ROLE = "asm.manage-roles-permissions.delete-role";
    public static final String ASM_ROLES_VIEW_ROLE = "asm.manage-roles-permissions.view-role";
    public static final String ASM_ROLES_ASSIGN_PERMISSIONS = "asm.manage-roles-permissions.assign-permissions";
}
