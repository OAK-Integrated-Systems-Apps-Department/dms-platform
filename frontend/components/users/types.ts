import type { AssignedUserStatus } from "@/components/roles/types"

/**
 * Shared types for the User Management feature (User List, View User, User
 * MFA Management, User Sessions).
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
 * A single active login session for a user, as returned by
 * `GET /admin/realms/{realm}/users/{id}/sessions`.
 */
export interface UserSessionEntry {
  id: string

  /**
   * Device/browser label as reported by Keycloak, e.g.
   * "Chrome on macOS".
   */
  device: string

  ip: string

  /**
   * ISO-ish "YYYY-MM-DD HH:mm" string, matching the wireframe's
   * "Started: YYYY-MM-DD HH:MM" format.
   */
  startedAt: string

  /**
   * Coarse geolocation string, e.g. "Colombo, LK". Best-effort — not
   * always available depending on IP geolocation configuration.
   */
  location: string
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

  // --- MFA-management-only fields, shown on User MFA Management (U8) ---

  /**
   * Whether the user currently has an MFA credential enrolled. Drives the
   * status chip on U8; `mfaStatus` above stays a free-text summary for the
   * View User screen, this is the structured flag U8's chip/actions
   * branch on.
   */
  mfaEnrolled: boolean

  /**
   * Registered credential type(s), e.g. "Authenticator app (TOTP)".
   * Empty when `mfaEnrolled` is false.
   *
   * Sourced from `GET /admin/realms/{realm}/users/{id}/credentials` in a
   * real integration.
   */
  mfaMethods: string[]

  /**
   * ISO date string, or null when `mfaEnrolled` is false.
   */
  mfaEnrolledOn: string | null

  /**
   * Whether the user's role/tenant currently mandates MFA. Independent of
   * `mfaEnrolled` — a policy can require MFA before the user has enrolled.
   */
  mfaRequiredByPolicy: boolean

  // --- Session-management-only fields, shown on User Sessions (U9) ---

  /**
   * Active login sessions. `activeSessions` above stays the plain count
   * shown on the View User screen; this is the detailed, per-session list
   * U9 lists and revokes from — kept in sync with `activeSessions.length`
   * whenever a session is revoked (see `stores/users-store.ts`).
   *
   * Sourced from `GET /admin/realms/{realm}/users/{id}/sessions` in a
   * real integration.
   */
  sessions: UserSessionEntry[]
}
