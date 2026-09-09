"use client"

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import {
  deactivateUser,
  fetchUser,
  fetchUserPlatformRoles,
  fetchUserRoles,
  fetchUsersPage,
  reactivateUser,
  sendPasswordReset as sendPasswordResetRequest,
  toUserRecord,
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
  const users: Awaited<ReturnType<typeof fetchUsersPage>>["content"] = []
  let page = 0
  while (true) {
    const response = await fetchUsersPage(page, PAGE_FETCH_SIZE)
    users.push(...response.content)
    if (response.last) break
    page += 1
  }

  // Promise.all rejects the WHOLE list the instant any single user's
  // /roles call fails — one bad row (a deactivated user hitting a
  // backend edge case, a transient network blip, whatever) then takes
  // down the page for every other user too, who are all otherwise fine.
  // Promise.allSettled + a per-user fallback keeps that failure scoped
  // to just the one row instead.
  const settled = await Promise.allSettled(
    users.map(async (user) => {
      const [roles, platformRoles] = await Promise.all([
        fetchUserRoles(user.userId),
        fetchUserPlatformRoles(user.userId),
      ])
      return toUserRecord(user, roles, platformRoles)
    })
  )

  return settled.map((result, index) => {
    if (result.status === "fulfilled") return result.value
    console.error(
      `Failed to load roles for user ${users[index].userId}:`,
      result.reason
    )
    return toUserRecord(users[index], [])
  })
}

/**
 * Loads every user in the caller's tenant from dms-core
 * (`GET /api/v1/users`, paged, plus `GET /api/v1/users/{id}/roles` per
 * user) for the User List screen, replacing the mock `users` array in
 * `stores/users-store.ts`.
 *
 * `GET /api/v1/users` deliberately still includes deactivated users (see
 * UserServiceImpl#listUsers on the backend) rather than filtering them
 * out, so a deactivated user stays visible in the table with an Inactive
 * badge instead of just disappearing on the next refetch.
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
      if (current.status === "active") {
        // A real soft-delete (DELETE /api/v1/users/{id}): removes the row
        // from every OTHER lookup (auth, duplicate-email checks) but not
        // from this list or the View User page — see deactivateUser in
        // lib/users/api.ts and UserServiceImpl#listUsers/#getUser.
        await deactivateUser(userId)
        return "deactivated" as const
      }
      // Reachable now: listUsers (above) no longer excludes deactivated
      // users, so a row for one really can be sitting here with
      // current.status !== "active". reactivateUser (POST
      // .../reactivate) is the endpoint that actually undoes
      // deactivateUser.
      await reactivateUser(userId)
      return "reactivated" as const
    },
    onSuccess: (action) => {
      queryClient.invalidateQueries({ queryKey: ["users"] })
      toast.success(action === "deactivated" ? "User deactivated." : "User reactivated.")
    },
    onError: (error) => {
      toast.error(error instanceof Error ? error.message : "Could not update this user's status.")
    },
  })

  const sendPasswordResetMutation = useMutation({
    mutationFn: (userId: string) => sendPasswordResetRequest(userId),
    onSuccess: () => {
      toast.success("Password reset email sent.")
    },
    onError: (error) => {
      toast.error(error instanceof Error ? error.message : "Could not send the password reset email.")
    },
  })

  // Standalone for the same reason as use-user.ts: toggleStatus's
  // mutationFn calls fetchUser(userId) first, which 404s for a genuinely
  // deactivated user (and that user has already vanished from `users`
  // above on the previous refetch, so there's no row here to click
  // "Activate" on in the first place). This makes the real reactivate
  // endpoint callable by id for whenever there's a UI surface — a
  // "Deactivated users" filter/tab, say — that can actually list them;
  // none exists yet.
  // const reactivateMutation = useMutation({
  //   mutationFn: (userId: string) => reactivateUserRequest(userId),
  //   onSuccess: () => {
  //     queryClient.invalidateQueries({ queryKey: ["users"] })
  //   },
  // })

  return {
    ...query,
    users: query.data ?? [],
    toggleStatus: (userId: string) => toggleStatusMutation.mutate(userId),
    sendPasswordReset: (userId: string) =>
      sendPasswordResetMutation.mutate(userId),
    // reactivate: (userId: string) => reactivateMutation.mutate(userId),
  }
}