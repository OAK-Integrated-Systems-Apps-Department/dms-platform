"use client"

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query"

import { API_BASE_URL } from "@/lib/api-config"

import type { UserRecord } from "@/components/users/types"

/** Mirrors backend/dms-core's UserResponse DTO. */
interface UserResponseDto {
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
interface UserRoleResponseDto {
  userRoleId: string
  roleName: string
}

async function parseErrorMessage(
  response: Response,
  fallback: string
): Promise<string> {
  const body = await response.json().catch(() => null)
  return body?.message ?? fallback
}

async function fetchUser(userId: string): Promise<UserResponseDto> {
  const response = await fetch(`${API_BASE_URL}/api/v1/users/${userId}`, {
    credentials: "include",
  })
  if (!response.ok) {
    throw new Error(await parseErrorMessage(response, "Could not load this user."))
  }
  return response.json()
}

async function fetchUserRoles(userId: string): Promise<UserRoleResponseDto[]> {
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
 * Maps the DTOs above onto `UserRecord` (components/users/types.ts) so
 * `UserDetailView` doesn't need to change to consume live data.
 *
 * A handful of `UserRecord` fields have no backing endpoint yet — MFA
 * method/enrollment detail, sessions, and activity/audit history all live
 * in Keycloak or an audit service that dms-core doesn't expose through
 * UserController today (see stores/users-store.ts's own placeholder
 * comments for the same gap). Those fall back to empty/"—" here rather
 * than being invented client-side.
 */
function toUserRecord(
  user: UserResponseDto,
  roles: UserRoleResponseDto[]
): UserRecord {
  const status: UserRecord["status"] = user.status === "active" ? "Active" : "Inactive"

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

/**
 * Loads a single user from dms-core (`GET /api/v1/users/{userId}` +
 * `GET /api/v1/users/{userId}/roles`) for the View User screen, replacing
 * the mock lookup in `stores/users-store.ts`.
 */
export function useUser(userId: string) {
  const query = useQuery({
    queryKey: ["user", userId],
    queryFn: async () => {
      const [user, roles] = await Promise.all([
        fetchUser(userId),
        fetchUserRoles(userId),
      ])
      return toUserRecord(user, roles)
    },
    retry: false,
    enabled: Boolean(userId),
  })

  const queryClient = useQueryClient()

  const toggleStatusMutation = useMutation({
    mutationFn: async () => {
      const current = await fetchUser(userId)
      const nextStatus = current.status === "active" ? "disabled" : "active"
      const response = await fetch(`${API_BASE_URL}/api/v1/users/${userId}`, {
        method: "PUT",
        credentials: "include",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          firstName: current.firstName,
          lastName: current.lastName,
          mfaEnabled: current.mfaEnabled,
          status: nextStatus,
        }),
      })
      if (!response.ok) {
        throw new Error(
          await parseErrorMessage(response, "Could not update this user's status.")
        )
      }
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["user", userId] })
    },
  })

  const sendPasswordResetMutation = useMutation({
    mutationFn: async () => {
      const response = await fetch(
        `${API_BASE_URL}/api/v1/users/${userId}/reset-password`,
        { method: "POST", credentials: "include" }
      )
      if (!response.ok) {
        throw new Error(
          await parseErrorMessage(response, "Could not send the password reset.")
        )
      }
    },
  })

  return {
    ...query,
    toggleStatus: () => toggleStatusMutation.mutate(),
    sendPasswordReset: () => sendPasswordResetMutation.mutate(),
  }
}