"use client"

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"

import {
  fetchUser,
  fetchUserRoles,
  sendPasswordReset as sendPasswordResetRequest,
  toUserRecord,
  updateUserStatus,
} from "@/lib/users/api"

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
      await updateUserStatus(userId, current, nextStatus)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["user", userId] })
    },
  })

  const sendPasswordResetMutation = useMutation({
    mutationFn: () => sendPasswordResetRequest(userId),
  })

  return {
    ...query,
    toggleStatus: () => toggleStatusMutation.mutate(),
    sendPasswordReset: () => sendPasswordResetMutation.mutate(),
  }
}