import { API_BASE_URL } from "@/lib/api-config"
import { apiFetch, apiFetchJson } from "@/lib/api-fetch"
import { formatDateTime } from "@/lib/utils"

import type { UserRecord } from "@/components/users/types"

/** Mirrors backend/dms-core's UserResponse DTO. */
export interface UserResponseDto {
  userId: string
  tenantId: string
  /** Human-readable tenant name, resolved server-side — null only if the tenant row is somehow missing/deleted. */
  tenantName: string | null
  keycloakId: string
  email: string
  /** The account's real login id, distinct from `email` — see `CreateUserRequest.username()`'s own doc comment on the backend. */
  username: string
  firstName: string
  lastName: string
  displayName: string
  mfaEnabled: boolean
  status: "active" | "locked" | "disabled"
  verified: boolean
  verifiedAt: string | null
  createdAt: string
  updatedAt: string | null
  /** ISO timestamp of this user's last successful login, or null if they've never logged in. */
  lastLoginAt: string | null
  /**
   * ISO timestamp of this user's last password change via our own
   * /update-password or /reset-password flow, or null if they've never
   * changed it under this tracking. Doesn't see a password changed
   * directly through Keycloak's own hosted Account Console — same class
   * of gap as `lastLoginAt` not seeing anything Keycloak-side.
   */
  lastPasswordChangedAt: string | null
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

export async function fetchUser(userId: string): Promise<UserResponseDto> {
  return apiFetchJson(`${API_BASE_URL}/api/v1/users/${userId}`, undefined, "Could not load this user.")
}

export async function fetchUserRoles(
  userId: string
): Promise<UserRoleResponseDto[]> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/users/${userId}/roles`,
    undefined,
    "Could not load this user's roles."
  )
}

/**
 * The fixed platform roles (PLATFORM_ADMIN/TENANT_ADMIN/MEMBER) this user
 * holds in Keycloak — see `UserRecord.platformRoles`'s doc comment for why
 * these are fetched and surfaced separately from `fetchUserRoles`.
 */
export async function fetchUserPlatformRoles(userId: string): Promise<string[]> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/users/${userId}/platform-roles`,
    undefined,
    "Could not load this user's platform roles."
  )
}

/**
 * The signed-in caller's own platform roles, via `GET /api/v1/users/me/platform-roles`.
 * Unlike `fetchUserPlatformRoles` above (which looks up some *other* user
 * and deliberately excludes PLATFORM_ADMIN — see that endpoint's backend
 * doc comment), this is self-referential and includes PLATFORM_ADMIN, so
 * it's what `useIsPlatformAdmin` calls to decide whether to show
 * platform-admin-only screens like Permission Catalog management.
 */
export async function fetchMyPlatformRoles(): Promise<string[]> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/users/me/platform-roles`,
    undefined,
    "Could not load your platform roles."
  )
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
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/users?page=${page}&size=${size}`,
    undefined,
    "Could not load users."
  )
}

/**
 * `reason` is mandatory server-side (UserController#deactivateUser requires
 * a non-blank `DeactivateUserRequest.reason` — see AuditActionCatalog's
 * USER_DEACTIVATED entry) and is recorded on the resulting audit event, not
 * stored on the user profile itself.
 */
export async function deactivateUser(userId: string, reason: string): Promise<void> {
  await apiFetch(
    `${API_BASE_URL}/api/v1/users/${userId}`,
    {
      method: "DELETE",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ reason }),
    },
    "Could not deactivate this user."
  )
}

/**
 * Backs the User List's Delete row action — a genuine hard delete
 * (`DELETE /api/v1/users/{userId}/permanent`, `UserController#purgeUser`):
 * the user's profile row and their Keycloak account are both actually
 * removed, not just marked inactive the way `deactivateUser` does. No
 * undo, unlike Deactivate/Reactivate. The backend additionally restricts
 * this endpoint to the tenant admin, on top of the `canDelete` gating
 * `UsersTable` already does client-side.
 */
export async function deleteUser(userId: string): Promise<void> {
  await apiFetch(
    `${API_BASE_URL}/api/v1/users/${userId}/permanent`,
    { method: "DELETE" },
    "Could not delete this user."
  )
}

/**
 * Undoes `deactivateUser` via the new `POST /api/v1/users/{userId}/reactivate`
 * endpoint. Doesn't need a `current` snapshot re-supplied like a PUT to
 * `/api/v1/users/{userId}` would — dms-core reads the existing row itself
 * and 409s if it isn't actually deactivated, so there's nothing for the
 * caller to get stale or resend.
 */
export async function reactivateUser(userId: string): Promise<UserResponseDto> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/users/${userId}/reactivate`,
    { method: "POST" },
    "Could not reactivate this user."
  )
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
 * `firstName`, `lastName`, `email`, `mfaEnabled`, and `status`, and returns
 * the updated `UserResponseDto`. Name and email are written to the user's
 * Keycloak account too. An email another account already has fails with
 * ApiError code `EMAIL_IN_USE` (409). Used by the Edit User form;
 * `mfaEnabled`/`status` are unrelated to what that form edits, so callers
 * should pass through a freshly fetched user's current values for those two
 * rather than guessing.
 */
export async function updateUserProfile(
  userId: string,
  patch: {
    firstName: string
    lastName: string
    email: string
    mfaEnabled: boolean
    status: UserResponseDto["status"]
  }
): Promise<UserResponseDto> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/users/${userId}`,
    {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(patch),
    },
    "Could not save changes."
  )
}

/**
 * `GET /api/v1/users/email-availability` - whether `email` is free to give
 * `userId` (leave out for a new user): no other user in the organisation,
 * and no account that signs in with it, has it. The same check a save makes.
 */
export async function isEmailAvailable(
  email: string,
  userId?: string
): Promise<boolean> {
  const params = new URLSearchParams({ email })
  if (userId) params.set("userId", userId)
  const result = await apiFetchJson<{ available: boolean }>(
    `${API_BASE_URL}/api/v1/users/email-availability?${params.toString()}`,
    undefined,
    "Could not check the email."
  )
  return result.available
}

/**
 * Self-service equivalent of `updateUserProfile`, via `PUT /api/v1/users/me`
 * — only `firstName`/`lastName`, matching `UpdateOwnProfileRequest` on the
 * backend. Used by My Profile's inline edit; unlike the admin Edit User
 * form, there's no `mfaEnabled`/`status`/roles to pass through here at all,
 * since this endpoint doesn't accept them (a caller can't use it to change
 * their own tenant, role, or account status).
 */
export async function updateCurrentUserProfile(patch: {
  firstName: string
  lastName: string
}): Promise<UserResponseDto> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/users/me`,
    {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(patch),
    },
    "Could not save changes."
  )
}

export async function sendPasswordReset(userId: string): Promise<void> {
  await apiFetch(
    `${API_BASE_URL}/api/v1/users/${userId}/reset-password`,
    { method: "POST" },
    "Could not send the password reset."
  )
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
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/users/${userId}/roles`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ roleId, scopeType: "tenant" }),
    },
    "Could not assign this role."
  )
}

/** Revokes a role assignment via `DELETE /api/v1/users/{userId}/roles/{userRoleId}` — also mirrored to Keycloak for tenant-wide assignments, same as `assignUserRole`. */
export async function revokeUserRole(
  userId: string,
  userRoleId: string
): Promise<void> {
  await apiFetch(
    `${API_BASE_URL}/api/v1/users/${userId}/roles/${userRoleId}`,
    { method: "DELETE" },
    "Could not remove this role."
  )
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
 * A handful of `UserRecord` fields still have no backing endpoint —  MFA
 * method/enrollment detail, sessions, and activity/audit history all live
 * in Keycloak or an audit service that dms-core doesn't expose through
 * UserController today (see stores/users-store.ts's own placeholder
 * comments for the same gap). Those fall back to empty/"—" here rather
 * than being invented client-side. `lastLogin`, `tenant`, and
 * `lastPasswordChange` are no longer among them — see
 * `UserResponseDto.lastLoginAt` (backed by `users.last_login_at`, written
 * by `AuthService#login` on every successful direct-grant login),
 * `UserResponseDto.tenantName` (resolved server-side from `Tenant`), and
 * `UserResponseDto.lastPasswordChangedAt` (backed by
 * `users.last_password_changed_at`, written by
 * `PasswordResetService#resetPassword` — the single place a real password
 * change actually completes, covering both the self-service and
 * admin-triggered reset flows). `lastPasswordChange` can't see a change
 * made directly through Keycloak's own hosted Account Console, which
 * stays a known gap, same as `lastLogin` not seeing anything
 * Keycloak-side.
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
    username: user.username,
    email: user.email,
    // Falls back to the raw id only in the (shouldn't-happen) case the
    // backend couldn't resolve a name for it — see UserResponseDto.tenantName.
    tenant: user.tenantName ?? user.tenantId,
    roles: roles.map((role) => role.roleName),
    platformRoles,
    status,
    verified: user.verified,
    // Date + time (not just the day, unlike createdOn below) — matches
    // the app's existing timestamp convention (see formatDateTime's own
    // doc comment). A user who has never logged in has lastLoginAt:
    // null, which falls back to "—" rather than a fabricated date.
    lastLogin: user.lastLoginAt ? formatDateTime(user.lastLoginAt) : "—",
    firstName: user.firstName,
    lastName: user.lastName,
    createdOn: user.createdAt.slice(0, 10),
    mfaStatus: user.mfaEnabled ? "Enabled" : "Not enrolled",
    // Date-only, matching createdOn above (and the mock data this
    // replaces) rather than lastLogin's date+time — a user who has never
    // changed their password under this tracking (including one still on
    // their initial invite-provisioned temporary password) has
    // lastPasswordChangedAt: null, which falls back to "—".
    lastPasswordChange: user.lastPasswordChangedAt ? user.lastPasswordChangedAt.slice(0, 10) : "—",
    activeSessions: 0,
    recentActivity: [],
    mfaEnrolled: user.mfaEnabled,
    mfaMethods: [],
    mfaEnrolledOn: null,
    mfaRequiredByPolicy: false,
    sessions: [],
  }
}