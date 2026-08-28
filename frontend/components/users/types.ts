/**
 * Shared types for the User Management feature (User List, View User).
 * Kept separate from components/roles/types.ts the same way that folder
 * is kept separate from components/dashboard/types.ts — one feature, one
 * types file.
 */

export type UserStatus = "active" | "inactive"

export interface UserActivityEntry {
  id: string
  /** ISO-ish "YYYY-MM-DD, HH:mm" string, matching dms-view-user.html's format. */
  time: string
  /** Rendered as-is; wrap the emphasized part in the caller if needed. */
  text: string
  actor: string
}

export interface UserRecord {
  id: string
  name: string
  username: string
  email: string
  /** Role names, matching RoleRecord.name in components/roles/types.ts. */
  roles: string[]
  status: UserStatus
  /** ISO date string, or "—" for a user who has never logged in. */
  lastLogin: string

  // --- Detail-only fields, shown on the View User screen ---
  firstName: string
  lastName: string
  tenant: string
  /** ISO date string. */
  createdOn: string
  mfaStatus: string
  lastPasswordChange: string
  activeSessions: number
  recentActivity: UserActivityEntry[]
}