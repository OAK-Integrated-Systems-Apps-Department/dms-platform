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
  verified: boolean
  verifiedAt: string | null
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
 * The fixed platform roles (PLATFORM_ADMIN/TENANT_ADMIN/MEMBER) this user
 * holds in Keycloak — see `UserRecord.platformRoles`'s doc comment for why
 * these are fetched and surfaced separately from `fetchUserRoles`.
 */
export async function fetchUserPlatformRoles(userId: string): Promise<string[]> {
  const response = await fetch(
    `${API_BASE_URL}/api/v1/users/${userId}/platform-roles`,
    { credentials: "include" }
  )
  if (!response.ok) {
    throw new Error(
      await parseErrorMessage(response, "Could not load this user's platform roles.")
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
 * Backs the User List's Delete row action. dms-core has no separate
 * hard-delete endpoint for users (see UserController#deactivateUser's own
 * doc comment) — `DELETE /api/v1/users/{userId}` already *is* the delete,
 * so this is a thin, separately-named wrapper around `deactivateUser`
 * rather than a new call. Kept as its own function (instead of just
 * reusing `deactivateUser` inline at every call site) so the Delete
 * action's call sites read as what they are and don't need a comment
 * explaining the reuse each time.
 */
export async function deleteUser(userId: string): Promise<void> {
  await deactivateUser(userId)
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
 * Grants a role to a user via `POST /api/v1/users/{userId}/roles`. Always
 * tenant-scoped (`scopeType: "tenant"`) — narrower scopes (department/
 * folder/document) have no UI surface yet, per `AssignRoleRequest.java`'s
 * own doc comment on why `scopeId` isn't a foreign key. This is also the
 * one call in this file that reaches Keycloak indirectly: dms-core mirrors
 * a tenant-wide assignment into a Keycloak realm role as part of the same
 * request (see `UserRoleServiceImpl.assignRole`), so a role granted here
 * shows up in both places, not just the local database.
 */
export async function assignUserRole(
  userId: string,
  roleId: string
): Promise<UserRoleResponseDto> {
  const response = await fetch(
    `${API_BASE_URL}/api/v1/users/${userId}/roles`,
    {
      method: "POST",
      credentials: "include",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ roleId, scopeType: "tenant" }),
    }
  )
  if (!response.ok) {
    throw new Error(await parseErrorMessage(response, "Could not assign this role."))
  }
  return response.json()
}

/** Revokes a role assignment via `DELETE /api/v1/users/{userId}/roles/{userRoleId}` — also mirrored to Keycloak for tenant-wide assignments, same as `assignUserRole`. */
export async function revokeUserRole(
  userId: string,
  userRoleId: string
): Promise<void> {
  const response = await fetch(
    `${API_BASE_URL}/api/v1/users/${userId}/roles/${userRoleId}`,
    { method: "DELETE", credentials: "include" }
  )
  if (!response.ok) {
    throw new Error(await parseErrorMessage(response, "Could not remove this role."))
  }
}

/**
 * Reconciles a user's tenant-wide role assignments to match
 * `desiredRoleNames`: re-fetches their current real assignments, diffs
 * against the desired set, and issues the necessary assign/revoke calls
 * in parallel. Mirrors the same shape `stores/roles-store.ts`'s
 * `syncRolePermissions` uses for a role's permissions — the backend only
 * exposes one-at-a-time assign/revoke, not a bulk "set to exactly this
 * list" endpoint, so this bridges that gap on the client.
 *
 * `availableRoles` resolves a selected name back to the `roleId`
 * `assignUserRole` needs — pass the live list from `useRolesStore`, not
 * a stale snapshot, since a role renamed between page load and save would
 * otherwise resolve to the wrong id or fail to resolve at all.
 */
export async function syncUserRoles(
  userId: string,
  desiredRoleNames: string[],
  availableRoles: { id: string; name: string }[]
): Promise<{ error: string | null }> {
  let currentGrants: UserRoleResponseDto[]
  try {
    currentGrants = await fetchUserRoles(userId)
  } catch (err) {
    return {
      error: err instanceof Error ? err.message : "Could not load this user's current roles.",
    }
  }

  const currentNames = new Set(currentGrants.map((g) => g.roleName))
  const desiredNames = new Set(desiredRoleNames)

  const toAssign = desiredRoleNames.filter((name) => !currentNames.has(name))
  const toRevoke = currentGrants.filter((g) => !desiredNames.has(g.roleName))

  if (toAssign.length === 0 && toRevoke.length === 0) {
    return { error: null }
  }

  const roleIdByName = new Map(availableRoles.map((r) => [r.name, r.id]))

  const results = await Promise.allSettled([
    ...toAssign.map((name) => {
      const roleId = roleIdByName.get(name)
      if (!roleId) {
        return Promise.reject(new Error(`"${name}" isn't in the current role list.`))
      }
      return assignUserRole(userId, roleId)
    }),
    ...toRevoke.map((grant) => revokeUserRole(userId, grant.userRoleId)),
  ])

  const failures = results.filter(
    (r): r is PromiseRejectedResult => r.status === "rejected"
  )
  if (failures.length === 0) return { error: null }
  if (failures.length === 1) {
    const reason = failures[0].reason
    return {
      error: reason instanceof Error ? reason.message : "Could not update this user's roles.",
    }
  }
  return { error: `${failures.length} role changes could not be applied. Please review and try again.` }
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
  roles: UserRoleResponseDto[],
  platformRoles: string[] = []
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
    platformRoles,
    status,
    verified: user.verified,
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