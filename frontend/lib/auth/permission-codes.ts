/**
 * Single source of truth for the granular permission codes passed to
 * `hasPermission()` / `hasAnyPermission()` (see `use-permissions.ts`) and to
 * `<PermissionGate>` across the Users and Roles admin screens. Mirrors
 * `PermissionCodes.java` on the backend - keep both in sync, and add a code
 * here only once the corresponding backend check exists.
 *
 * `LEGACY_ROLE_VIEW`/`LEGACY_ROLE_MANAGE` are the two coarse codes that
 * remain literally correct to check: `PermissionController` (Permission
 * Catalog screens) was out of scope for the granular sweep and still
 * checks these two directly, unlike `UserController`/`UserRoleController`/
 * `RoleController`/`RolePermissionController`, which now check the
 * granular codes below instead.
 */
export const PermissionCodes = {
  // Users (OTUA - Organization, Tenant and User Administration)
  OTUA_USERS_CREATE: "otua.manage-users.create",
  OTUA_USERS_UPDATE: "otua.manage-users.update",
  OTUA_USERS_VIEW: "otua.manage-users.view",
  OTUA_USERS_RESET_PASSWORD: "otua.manage-users.reset-password",
  OTUA_USERS_ACTIVATE_DEACTIVATE: "otua.manage-users.activate-deactivate",
  OTUA_USERS_ASSIGN_ROLE: "otua.manage-users.assign-role",

  // Roles (ASM - Access and Security Management)
  ASM_ROLES_CREATE_ROLE: "asm.manage-roles-permissions.create-role",
  ASM_ROLES_UPDATE_ROLE: "asm.manage-roles-permissions.update-role",
  ASM_ROLES_DELETE_ROLE: "asm.manage-roles-permissions.delete-role",
  ASM_ROLES_VIEW_ROLE: "asm.manage-roles-permissions.view-role",
  ASM_ROLES_ASSIGN_PERMISSIONS: "asm.manage-roles-permissions.assign-permissions",

  // Legacy - Permission Catalog screens only (PermissionController), see doc comment above.
  LEGACY_ROLE_VIEW: "role.view",
  LEGACY_ROLE_MANAGE: "role.manage",
} as const
