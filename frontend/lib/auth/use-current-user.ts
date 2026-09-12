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
}

function initialsOf(displayName: string): string {
  const parts = displayName.trim().split(/\s+/)
  const first = parts[0]?.[0] ?? ""
  const last = parts.length > 1 ? (parts[parts.length - 1]?.[0] ?? "") : ""
  return (first + last).toUpperCase() || "?"
}

async function fetchCurrentUser(): Promise<CurrentUser> {
  return apiFetchJson(`${API_BASE_URL}/api/v1/users/me`, undefined, "Could not load your profile.")
}

/**
 * Loads the real signed-in user from dms-core (GET /api/v1/users/me),
 * replacing the mock `currentUser` in components/dashboard/data.ts.
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