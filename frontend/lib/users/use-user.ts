"use client"

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import {
  deactivateUser,
  fetchUser,
  fetchUserPlatformRoles,
  fetchUserRoles,
  reactivateUser,
  sendPasswordReset as sendPasswordResetRequest,
  toUserRecord,
} from "@/lib/users/api"

/**
 * Loads a single user from dms-core (`GET /api/v1/users/{userId}` +
 * `GET /api/v1/users/{userId}/roles` + `GET /api/v1/users/{userId}/platform-roles`)
 * for the View User screen, replacing the mock lookup in
 * `stores/users-store.ts`.
 *
 * `GET /api/v1/users/{userId}` deliberately still returns a deactivated
 * user (see UserServiceImpl#getUser on the backend) rather than 404ing,
 * specifically so this page keeps working for one and can offer
 * Reactivate instead of just vanishing.
 */
export function useUser(userId: string) {
  const query = useQuery({
    queryKey: ["user", userId],
    queryFn: async () => {
      const [user, roles, platformRoles] = await Promise.all([
        fetchUser(userId),
        fetchUserRoles(userId),
        fetchUserPlatformRoles(userId),
      ])
      return toUserRecord(user, roles, platformRoles)
    },
    retry: false,
    enabled: Boolean(userId),
  })

  const queryClient = useQueryClient()

  const toggleStatusMutation = useMutation({
    mutationFn: async () => {
      const current = await fetchUser(userId)
      if (current.status === "active") {
        // A real soft-delete (DELETE /api/v1/users/{id}), not a
        // status-field PUT — see deactivateUser in lib/users/api.ts.
        await deactivateUser(userId)
        return "deactivated" as const
      }
      // Reachable now: getUser (above) no longer excludes deactivated
      // users, so `current` here really can be one, and reactivateUser
      // (POST .../reactivate) is the endpoint that actually undoes
      // deactivateUser.
      await reactivateUser(userId)
      return "reactivated" as const
    },
    onSuccess: (action) => {
      queryClient.invalidateQueries({ queryKey: ["user", userId] })
      toast.success(action === "deactivated" ? "User deactivated." : "User reactivated.")
    },
    onError: (error) => {
      toast.error(error instanceof Error ? error.message : "Could not update this user's status.")
    },
  })

  const sendPasswordResetMutation = useMutation({
    mutationFn: () => sendPasswordResetRequest(userId),
    onSuccess: () => {
      toast.success("Password reset email sent.")
    },
    onError: (error) => {
      toast.error(error instanceof Error ? error.message : "Could not send the password reset email.")
    },
  })

  // Standalone rather than folded into toggleStatus above: toggleStatus's
  // mutationFn calls fetchUser(userId) first to read the current status,
  // and fetchUser 404s for any user that's actually deactivated (same
  // deleted=false filter as every other lookup) — so that branch can
  // structurally never reach a genuinely-deactivated user, and neither
  // can this whole page (its own top-level query would already have
  // 404'd and redirected to not-found before this dialog could render).
  // This exists so the real reactivate endpoint is available to call by
  // id once there's a UI surface that can list/find deactivated users —
  // there isn't one today.

  // const reactivateMutation = useMutation({
  //   mutationFn: () => reactivateUserRequest(userId),
  //   onSuccess: () => {
  //     queryClient.invalidateQueries({ queryKey: ["user", userId] })
  //   },
  // })

  return {
    ...query,
    toggleStatus: () => toggleStatusMutation.mutate(),
    sendPasswordReset: () => sendPasswordResetMutation.mutate(),
    // reactivate: () => reactivateMutation.mutate(),
  }
}