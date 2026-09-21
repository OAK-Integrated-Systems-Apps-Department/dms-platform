package com.oak.dms.security.rbac.catalog;

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
 * @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_USERS_CREATE)")
 * }</pre>
 * Not every permission in the catalog has a constant here - only the ones
 * actually referenced by an {@code @PreAuthorize} check.
 * <p>
 * {@code OTUA_USERS_ACTIVATE}/{@code OTUA_USERS_DEACTIVATE} replace a
 * single {@code otua.manage-users.activate-deactivate} code that used to
 * gate both directions together - split so a role can be granted one
 * without the other (e.g. an offboarding-only role that can deactivate
 * but never reactivate). See {@code V20__split_activate_deactivate_and_wire_delete_user.sql}.
 * {@code OTUA_USERS_DELETE} now also gates {@code UserController#purgeUser},
 * in addition to (not instead of) the tenant-admin bypass every check
 * here already gets via {@code AuthorizationService#hasFullAccess} - see
 * that method's own doc comment for the tradeoff this accepts.
 */
public final class PermissionCodes {

    private PermissionCodes() {
    }

    // Users - OTUA (Organization, Tenant and User Administration) / Manage Users
    public static final String OTUA_USERS_CREATE = "otua.manage-users.create";
    public static final String OTUA_USERS_UPDATE = "otua.manage-users.update";
    public static final String OTUA_USERS_VIEW = "otua.manage-users.view";
    public static final String OTUA_USERS_RESET_PASSWORD = "otua.manage-users.reset-password";
    public static final String OTUA_USERS_ACTIVATE = "otua.manage-users.activate";
    public static final String OTUA_USERS_DEACTIVATE = "otua.manage-users.deactivate";
    public static final String OTUA_USERS_DELETE = "otua.manage-users.delete";
    public static final String OTUA_USERS_ASSIGN_ROLE = "otua.manage-users.assign-role";
    /** Gates {@code ImpersonationController#start} - see that class and {@code ImpersonationContextFilter} for the full "Act as User" mechanism. */
    public static final String OTUA_USERS_IMPERSONATE = "otua.manage-users.impersonate";

    // Roles - ASM (Access and Security Management) / Manage Roles & Permissions
    public static final String ASM_ROLES_CREATE_ROLE = "asm.manage-roles-permissions.create-role";
    public static final String ASM_ROLES_UPDATE_ROLE = "asm.manage-roles-permissions.update-role";
    public static final String ASM_ROLES_DELETE_ROLE = "asm.manage-roles-permissions.delete-role";
    public static final String ASM_ROLES_VIEW_ROLE = "asm.manage-roles-permissions.view-role";
    public static final String ASM_ROLES_ASSIGN_PERMISSIONS = "asm.manage-roles-permissions.assign-permissions";

    // Metadata & Document-Type Management - MDTM (module 2.7)
    //
    // Extends the MDTM module/action rows already seeded by V6/V10 rather
    // than introducing new resource names: document types are
    // "manage-content-types" and metadata fields are "manage-metadata-schema"
    // in the DB catalog, even though the module 2.7 requirement doc talks
    // about "document types" and "metadata fields" - see V24 for the
    // additional view/activate-deactivate/deprecate-field/type-field-mapping
    // action rows this module needed that V10 never seeded (it only ever
    // seeded create/update/delete-shaped actions).
    public static final String MDTM_CONTENT_TYPES_CREATE = "mdtm.manage-content-types.create";
    public static final String MDTM_CONTENT_TYPES_UPDATE = "mdtm.manage-content-types.update";
    public static final String MDTM_CONTENT_TYPES_DELETE = "mdtm.manage-content-types.delete";
    public static final String MDTM_CONTENT_TYPES_VIEW = "mdtm.manage-content-types.view";
    public static final String MDTM_CONTENT_TYPES_ACTIVATE_DEACTIVATE = "mdtm.manage-content-types.activate-deactivate";

    public static final String MDTM_METADATA_SCHEMA_CREATE_FIELD = "mdtm.manage-metadata-schema.create-field";
    public static final String MDTM_METADATA_SCHEMA_UPDATE_FIELD = "mdtm.manage-metadata-schema.update-field";
    public static final String MDTM_METADATA_SCHEMA_DELETE_FIELD = "mdtm.manage-metadata-schema.delete-field";
    public static final String MDTM_METADATA_SCHEMA_VIEW_FIELD = "mdtm.manage-metadata-schema.view-field";
    public static final String MDTM_METADATA_SCHEMA_ACTIVATE_DEACTIVATE_FIELD = "mdtm.manage-metadata-schema.activate-deactivate-field";
    public static final String MDTM_METADATA_SCHEMA_DEPRECATE_FIELD = "mdtm.manage-metadata-schema.deprecate-field";
    public static final String MDTM_METADATA_SCHEMA_SET_VALIDATION_RULES = "mdtm.manage-metadata-schema.set-validation-rules";

    public static final String MDTM_TYPE_FIELD_MAPPING_VIEW = "mdtm.manage-type-field-mapping.view";
    public static final String MDTM_TYPE_FIELD_MAPPING_UPDATE = "mdtm.manage-type-field-mapping.update";

    // Audit - ABDR (Comprehensive Audit Management) / View Audit History
    public static final String ABDR_VIEW_AUDIT_HISTORY = "abdr.view-audit-history";
    // Distinct from ABDR_VIEW_AUDIT_HISTORY on purpose - exporting the trail
    // (a bulk copy leaving the system) is a stricter capability than
    // viewing it on-screen, and the seeded catalog (V10) already reserves a
    // separate code for it rather than folding it into "view".
    public static final String ABDR_EXPORT_AUDIT_REPORTS = "abdr.export-audit-reports";
}
