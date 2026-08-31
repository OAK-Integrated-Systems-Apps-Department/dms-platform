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
}

export interface PermissionGroup {
  /** Functional area heading, e.g. "Document Management". */
  area: string
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
