import { API_BASE_URL } from "@/lib/api-config"

import type { UserRecord } from "@/components/users/types"

/** Mirrors backend/dms-core's UserResponse DTO. */
export interface UserResponseDto {
  userId: string
  tenantId: string
  keycloakId: string
  email: string
  firstName: string
  lastName: string
  displayName: string
  mfaEnabled: boolean
  status: "active" | "locked" | "disabled"
  createdAt: string
  updatedAt: string | null
}

/** Mirrors backend/dms-core's UserRoleResponse DTO (only the fields the UI uses). */
export interface UserRoleResponseDto {
  userRoleId: string
  roleName: string
}

/** Mirrors backend/dms-core's PageResponse<UserResponse> envelope. */
export interface UserPageResponseDto {
  content: UserResponseDto[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  last: boolean
}

export async function parseErrorMessage(
  response: Response,
  fallback: string
): Promise<string> {
  const body = await response.json().catch(() => null)
  return body?.message ?? fallback
}

export async function fetchUser(userId: string): Promise<UserResponseDto> {
  const response = await fetch(`${API_BASE_URL}/api/v1/users/${userId}`, {
    credentials: "include",
  })
  if (!response.ok) {
    throw new Error(await parseErrorMessage(response, "Could not load this user."))
  }
  return response.json()
}

export async function fetchUserRoles(
  userId: string
): Promise<UserRoleResponseDto[]> {
  const response = await fetch(
    `${API_BASE_URL}/api/v1/users/${userId}/roles`,
    { credentials: "include" }
  )
  if (!response.ok) {
    throw new Error(
      await parseErrorMessage(response, "Could not load this user's roles.")
    )
  }
  return response.json()
}

/**
 * One page of `GET /api/v1/users`. The endpoint takes a Spring `Pageable`
 * (`page`, `size`, `sort` query params) and returns no search or role
 * filter of its own — those stay client-side in `useUsers`, same as they
 * were against the mock store.
 */
export async function fetchUsersPage(
  page: number,
  size: number
): Promise<UserPageResponseDto> {
  const response = await fetch(
    `${API_BASE_URL}/api/v1/users?page=${page}&size=${size}`,
    { credentials: "include" }
  )
  if (!response.ok) {
    throw new Error(await parseErrorMessage(response, "Could not load users."))
  }
  return response.json()
}

export async function deactivateUser(userId: string): Promise<void> {
  const response = await fetch(`${API_BASE_URL}/api/v1/users/${userId}`, {
    method: "DELETE",
    credentials: "include",
  })
  if (!response.ok) {
    throw new Error(
      await parseErrorMessage(response, "Could not deactivate this user.")
    )
  }
}

/**
 * Undoes `deactivateUser` via the new `POST /api/v1/users/{userId}/reactivate`
 * endpoint. Doesn't need a `current` snapshot re-supplied like a PUT to
 * `/api/v1/users/{userId}` would — dms-core reads the existing row itself
 * and 409s if it isn't actually deactivated, so there's nothing for the
 * caller to get stale or resend.
 */
export async function reactivateUser(userId: string): Promise<UserResponseDto> {
  const response = await fetch(
    `${API_BASE_URL}/api/v1/users/${userId}/reactivate`,
    { method: "POST", credentials: "include" }
  )
  if (!response.ok) {
    throw new Error(
      await parseErrorMessage(response, "Could not reactivate this user.")
    )
  }
  return response.json()
}

// /**
//  * Re-enables a user via `PUT /api/v1/users/{userId}` with `status: "active"`
//  * — for a user whose status is "locked"/"disabled" but who was never
//  * actually deactivated (soft-deleted). For a genuinely deactivated user,
//  * use `reactivateUser` above instead — this PUT's own lookup filters out
//  * deleted rows the same as every other endpoint, so it 404s for them.
//  */
// export async function activateUser(
//   userId: string,
//   current: UserResponseDto
// ): Promise<void> {
//   await updateUserStatus(userId, current, "active")
// }

// export async function updateUserStatus(
//   userId: string,
//   current: UserResponseDto,
//   nextStatus: UserResponseDto["status"]
// ): Promise<void> {
//   const response = await fetch(`${API_BASE_URL}/api/v1/users/${userId}`, {
//     method: "PUT",
//     credentials: "include",
//     headers: { "Content-Type": "application/json" },
//     body: JSON.stringify({
//       firstName: current.firstName,
//       lastName: current.lastName,
//       mfaEnabled: current.mfaEnabled,
//       status: nextStatus,
//     }),
//   })
//   if (!response.ok) {
//     throw new Error(
//       await parseErrorMessage(response, "Could not update this user's status.")
//     )
//   }
// }

/**
 * Full profile update via `PUT /api/v1/users/{userId}` — accepts
 * `firstName`, `lastName`, `mfaEnabled`, and `status`, and returns the
 * updated `UserResponseDto` (see the schema on the endpoint's Swagger
 * page). Used by the Edit User form; `mfaEnabled`/`status` are unrelated
 * to what that form edits, so callers should pass through a freshly
 * fetched user's current values for those two rather than guessing.
 */
export async function updateUserProfile(
  userId: string,
  patch: {
    firstName: string
    lastName: string
    mfaEnabled: boolean
    status: UserResponseDto["status"]
  }
): Promise<UserResponseDto> {
  const response = await fetch(`${API_BASE_URL}/api/v1/users/${userId}`, {
    method: "PUT",
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(patch),
  })
  if (!response.ok) {
    throw new Error(
      await parseErrorMessage(response, "Could not save changes.")
    )
  }
  return response.json()
}

export async function sendPasswordReset(userId: string): Promise<void> {
  const response = await fetch(
    `${API_BASE_URL}/api/v1/users/${userId}/reset-password`,
    { method: "POST", credentials: "include" }
  )
  if (!response.ok) {
    throw new Error(
      await parseErrorMessage(response, "Could not send the password reset.")
    )
  }
}

/**
 * Maps the DTOs above onto `UserRecord` (components/users/types.ts) so
 * `UserDetailView` / `UsersTable` don't need to change to consume live
 * data.
 *
 * A handful of `UserRecord` fields have no backing endpoint yet — MFA
 * method/enrollment detail, sessions, last login, and activity/audit
 * history all live in Keycloak or an audit service that dms-core doesn't
 * expose through UserController today (see stores/users-store.ts's own
 * placeholder comments for the same gap). Those fall back to empty/"—"
 * here rather than being invented client-side.
 */
export function toUserRecord(
  user: UserResponseDto,
  roles: UserRoleResponseDto[]
): UserRecord {
  const status: UserRecord["status"] =
    user.status === "active" ? "Active" : "Inactive"

  return {
    id: user.userId,
    name: user.displayName,
    // dms-core has no separate username field — derive a display-only one
    // from the email's local part rather than repeating the full email
    // next to itself in the header.
    username: user.email.split("@")[0],
    email: user.email,
    // No tenant-name lookup exists yet; showing the id is better than
    // hiding the field entirely.
    tenant: user.tenantId,
    roles: roles.map((role) => role.roleName),
    status,
    lastLogin: "—",
    firstName: user.firstName,
    lastName: user.lastName,
    createdOn: user.createdAt.slice(0, 10),
    mfaStatus: user.mfaEnabled ? "Enabled" : "Not enrolled",
    lastPasswordChange: "—",
    activeSessions: 0,
    recentActivity: [],
    mfaEnrolled: user.mfaEnabled,
    mfaMethods: [],
    mfaEnrolledOn: null,
    mfaRequiredByPolicy: false,
    sessions: [],
  }
}