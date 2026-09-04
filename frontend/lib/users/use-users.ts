"use client"

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"

import {
  fetchUser,
  fetchUserRoles,
  fetchUsersPage,
  sendPasswordReset as sendPasswordResetRequest,
  toUserRecord,
  updateUserStatus,
} from "@/lib/users/api"

import type { UserRecord } from "@/components/users/types"

// GET /api/v1/users has no search/role/status query params of its own —
// the User List page filters/sorts/paginates client-side exactly like it
// did against the mock store, so this pulls every user in the tenant once
// (paging through dms-core's Pageable in chunks of PAGE_FETCH_SIZE) rather
// than re-querying per keystroke. Fine for a tenant's admin/user roster;
// would need to move to server-side search for a very large tenant.
const PAGE_FETCH_SIZE = 100

async function fetchAllUsers(): Promise<UserRecord[]> {
  const users = []
  let page = 0
  while (true) {
    const response = await fetchUsersPage(page, PAGE_FETCH_SIZE)
    users.push(...response.content)
    if (response.last) break
    page += 1
  }

  const records = await Promise.all(
    users.map(async (user) => {
      const roles = await fetchUserRoles(user.userId)
      return toUserRecord(user, roles)
    })
  )
  return records
}

/**
 * Loads every user in the caller's tenant from dms-core
 * (`GET /api/v1/users`, paged, plus `GET /api/v1/users/{id}/roles` per
 * user) for the User List screen, replacing the mock `users` array in
 * `stores/users-store.ts`.
 */
export function useUsers() {
  const query = useQuery({
    queryKey: ["users"],
    queryFn: fetchAllUsers,
    retry: false,
  })

  const queryClient = useQueryClient()

  const toggleStatusMutation = useMutation({
    mutationFn: async (userId: string) => {
      const current = await fetchUser(userId)
      const nextStatus = current.status === "active" ? "disabled" : "active"
      await updateUserStatus(userId, current, nextStatus)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["users"] })
    },
  })

  const sendPasswordResetMutation = useMutation({
    mutationFn: (userId: string) => sendPasswordResetRequest(userId),
  })

  return {
    ...query,
    users: query.data ?? [],
    toggleStatus: (userId: string) => toggleStatusMutation.mutate(userId),
    sendPasswordReset: (userId: string) =>
      sendPasswordResetMutation.mutate(userId),
  }
}