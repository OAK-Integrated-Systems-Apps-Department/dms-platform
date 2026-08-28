/**
 * Shared types for the User Management feature (User List). Kept
 * separate from components/roles/types.ts the same way that folder is
 * kept separate from components/dashboard/types.ts — one feature, one
 * types file.
 */

export type UserStatus = "active" | "inactive"

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
}