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
  const { data: currentUser } = useCurrentUser()

  const query = useQuery({
    queryKey: ["current-user-platform-roles", currentUser?.userId],
    queryFn: () => fetchUserPlatformRoles(currentUser!.userId),
    enabled: !!currentUser?.userId,
    retry: false,
  })

  return {
    ...query,
    isTenantAdmin: query.data?.includes("TENANT_ADMIN") ?? false,
  }
}