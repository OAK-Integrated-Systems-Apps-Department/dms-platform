/**
 * Shared types for the Role Management feature (Role List, Add/Edit Role).
 * Keeping these separate from `components/dashboard/types.ts` mirrors how
 * that folder keeps its own feature-scoped types.
 */

export type RoleStatus = "Active" | "Inactive"

export interface PermissionItem {
  /** Stable id used for storage/lookup, e.g. "doc.upload". */
  id: string
  /** Human-readable action name shown in the checkbox row. */
  label: string
  /**
   * Fine-grained permissions nested under this action (e.g. action
   * "Manage Users" -> permissions "Create user", "Update user", "Reset
   * user password", ...), matching the Module -> Action -> Permission
   * breakdown in the Permission Catalog. When present, this item's own
   * `id` is a grouping label only — it is never itself grantable and
   * never appears in `RoleRecord.permissions`; roles grant the specific
   * child permission ids instead. Optional because most actions stay
   * single-level and remain directly grantable, as before.
   */
  permissions?: PermissionItem[]
}

export interface PermissionGroup {
  /** Functional module name, e.g. "Organization, Tenant and User Administration". */
  module: string
  /** Short module code from the Permission Catalog, e.g. "OTUA". */
  moduleCode: string
  permissions: PermissionItem[]
}

export interface RoleRecord {
  id: string
  name: string
  description: string
  status: RoleStatus
  /** Number of users currently holding this role. */
  assignedUsers: number
  /** Ids of the permissions this role grants, across all groups. */
  permissions: string[]
  /**
   * System (built-in) roles can be viewed but not edited, deactivated, or
   * deleted — see the caveat on the Role List wireframe screen.
   */
  system?: boolean
}

export type AssignedUserStatus = "Active" | "Inactive"
