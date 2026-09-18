"use client"

import { useQuery } from "@tanstack/react-query"

import { fetchUsersPage } from "@/lib/users/api"

import type { ActorOption } from "@/components/audit-log/actor-filter-select"

/** First page is plenty for a filter dropdown; a tenant with more than this many users would need real server-side actor search instead - not built here. */
const ACTOR_OPTIONS_PAGE_SIZE = 200

/**
 * Options for the audit-log actor filter - id+displayName only, via the
 * same `GET /api/v1/users` the User List uses, but skipping the
 * per-user role lookups `useUsers`/`fetchAllUsers` also does (this
 * filter has no use for roles).
 *
 * `enabled` must reflect whether the caller can actually reach
 * `GET /api/v1/users` at all - a caller with only `abdr.view-audit-history`
 * and no `otua.manage-users.*` permission would otherwise 403 on this
 * call every time the audit log page loads, for a dropdown they can't use
 * anyway. Pass `false` for such a caller (see `page.tsx`'s `canFilterByActor`)
 * rather than letting this fetch fire unconditionally.
 */
export function useActorOptions(enabled: boolean): ActorOption[] {
  const query = useQuery({
    queryKey: ["audit-events", "actor-options"],
    queryFn: async (): Promise<ActorOption[]> => {
      const firstPage = await fetchUsersPage(0, ACTOR_OPTIONS_PAGE_SIZE)
      return firstPage.content.map((user) => ({ id: user.userId, label: user.displayName }))
    },
    enabled,
    staleTime: 5 * 60 * 1000,
  })
  return query.data ?? []
}
