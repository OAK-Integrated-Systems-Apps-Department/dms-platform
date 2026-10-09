"use client"

import { useQuery } from "@tanstack/react-query"

import { useCurrentUser } from "@/lib/auth/use-current-user"
import { fetchUserPlatformRoles } from "@/lib/users/api"

/**
 * Whether the signed-in user holds the TENANT_ADMIN platform role —
 * gates the User List's Delete row action (only the tenant admin can
 * delete another account; see `UsersTable`'s `canDelete` prop). Separate
 * from `useCurrentUser` itself since platform roles come from their own
 * endpoint (`GET /api/v1/users/{id}/platform-roles`, same one
 * `useUsers`'s `fetchAllUsers` calls per row) rather than
 * `GET /api/v1/users/me`.
 */
export function useIsTenantAdmin() {
  const { data: currentUser, isError: noLocalProfile } = useCurrentUser()

  const query = useQuery({
    queryKey: ["current-user-platform-roles", currentUser?.userId],
    queryFn: () => fetchUserPlatformRoles(currentUser!.userId),
    enabled: !!currentUser?.userId,
    retry: false,
  })

  return {
    ...query,
    // A caller with no local user row - a platform administrator, for whom
    // `GET /api/v1/users/me` answers 401 TENANT_CONTEXT_MISSING by design -
    // leaves this query permanently disabled, and a disabled query reports
    // `isPending: true` forever. Callers read that as "still checking" and
    // sit on a spinner for the rest of the session. Such a caller plainly
    // isn't a tenant admin, so report the question as settled and answered
    // "no" rather than never answered at all.
    isPending: query.isPending && !noLocalProfile,
    isTenantAdmin: query.data?.includes("TENANT_ADMIN") ?? false,
  }
}
