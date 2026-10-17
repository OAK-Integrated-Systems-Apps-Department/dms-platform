"use client"

import { useQuery } from "@tanstack/react-query"

import { API_BASE_URL } from "@/lib/api-config"
import { apiFetchJson } from "@/lib/api-fetch"

/** Mirrors backend/dms-core's UserResponse DTO (only the fields the UI uses). */
export interface CurrentUser {
  userId: string
  tenantId: string
  email: string
  firstName: string
  lastName: string
  displayName: string
  mfaEnabled: boolean
  status: string
  createdAt: string
  /** ISO timestamp of this user's last password change via our own /update-password or /reset-password flow, or null if they've never changed it under this tracking. Doesn't see a change made directly through Keycloak's own hosted Account Console. */
  lastPasswordChangedAt: string | null
  /** ISO timestamp stamped by dms-core on every successful sign-in (`AuthService#recordLastLogin`) - so while signed in, this is the current session's sign-in time. Null if the user has never signed in. */
  lastLoginAt: string | null
  /** Role names for display only (never for gating - use `permissions`): fixed platform roles (`PLATFORM_ADMIN`/`TENANT_ADMIN`/`MEMBER`) first, then the tenant-defined roles that currently apply. `/me`-only, like `permissions`. */
  roles: string[]
  /**
   * The caller's own resolved permission codes (role assignments'
   * grants, flattened/deduplicated server-side — see
   * `AuthorizationService#myEffectivePermissionCodes`). Only ever
   * present on this "me" call, not on any other endpoint that happens to
   * return the same shape. `usePermissions()` is the intended way to
   * read this — components shouldn't reach into `useCurrentUser` for it
   * directly.
   */
  permissions: string[]
  /**
   * Whether the current session is operating under an "Act as User"
   * impersonation grant - server-computed from the trusted session context
   * (see backend UserResponse's own doc comment), never something the
   * frontend can set or infer on its own. `ImpersonationBanner` is the
   * only reader of this and `impersonatorDisplayName`.
   */
  impersonating: boolean
  /** The real admin's display name while impersonating; null otherwise. */
  impersonatorDisplayName: string | null
}

/** "Ada Lovelace" -> "AL". Exported so the account-menu fallback derives initials the same way. */
export function initialsOf(displayName: string): string {
  const parts = displayName.trim().split(/\s+/)
  const first = parts[0]?.[0] ?? ""
  const last = parts.length > 1 ? (parts[parts.length - 1]?.[0] ?? "") : ""
  return (first + last).toUpperCase() || "?"
}

async function fetchCurrentUser(): Promise<CurrentUser> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/users/me`,
    undefined,
    "Could not load your profile."
  )
}

/**
 * Loads the real signed-in user from dms-core (GET /api/v1/users/me),
 * which replaced the dashboard's old mock user.
 * `initials` is derived client-side since the backend doesn't return one.
 */
export function useCurrentUser() {
  const query = useQuery({
    queryKey: ["current-user"],
    queryFn: fetchCurrentUser,
    retry: false,
  })

  return {
    ...query,
    initials: query.data ? initialsOf(query.data.displayName) : undefined,
  }
}
