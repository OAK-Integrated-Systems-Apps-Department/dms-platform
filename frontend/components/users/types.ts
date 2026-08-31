import type { AssignedUserStatus } from "@/components/roles/types"

/**
 * Shared types for the User Management feature (User List, View User).
 *
 * Kept separate from components/roles/types.ts the same way that folder
 * is kept separate from components/dashboard/types.ts — one feature, one
 * types file.
 *
 * User status reuses AssignedUserStatus from the Roles feature so the same
 * user has a consistent status representation across Role Details and
 * User Management.
 */

/**
 * A DMS user's activity entry.
 *
 * ISO-ish "YYYY-MM-DD, HH:mm" string, matching dms-view-user.html's format.
 */
export interface UserActivityEntry {
  id: string

  /**
   * ISO-ish "YYYY-MM-DD, HH:mm" string, matching
   * dms-view-user.html's format.
   */
  time: string

  /**
   * Rendered as-is; wrap the emphasized part in the caller if needed.
   */
  text: string

  actor: string
}

/**
 * A DMS user.
 *
 * Kept structurally close to `AssignedUserStatus` (`components/roles/types.ts`)
 * for the status field, plus email and tenant that Role Details doesn't
 * need, so the same person shows up consistently across Role Details'
 * assigned-users table and User Access Summary.
 *
 * Sourced from Keycloak's admin user list
 * (`GET /admin/realms/{realm}/users`) in a real integration.
 */
export interface UserRecord {
  id: string
  name: string
  username: string
  email: string

  /**
   * Tenant the user belongs to.
   */
  tenant: string

  /**
   * Role names, matching RoleRecord.name in components/roles/types.ts.
   */
  roles: string[]

  /**
   * Reuses the Roles feature's status type so User Management and
   * Role Details use the same "Active" / "Inactive" values.
   */
  status: AssignedUserStatus

  /**
   * ISO date string, or "—" for a user who has never logged in.
   */
  lastLogin: string

  // --- Detail-only fields, shown on the View User screen ---

  firstName: string
  lastName: string

  /**
   * ISO date string.
   */
  createdOn: string

  mfaStatus: string
  lastPasswordChange: string
  activeSessions: number
  recentActivity: UserActivityEntry[]
}
