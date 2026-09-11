"use client"

import { useQuery } from "@tanstack/react-query"

import { useCurrentUser } from "@/lib/auth/use-current-user"
import { fetchMyPlatformRoles } from "@/lib/users/api"

/**
 * Whether the signed-in user holds the cross-tenant PLATFORM_ADMIN role —
 * gates the Permission Catalog's create/edit/delete UI (Add Permission,
 * Edit Permission, the delete dialog): a permission invented by a tenant
 * admin at runtime has no matching `@PreAuthorize` check anywhere in the
 * backend, so create/update/delete are now PLATFORM_ADMIN-only there too
 * (see `PermissionController`). Tenant admins still see the read-only
 * Permission List used inside Role Permission Configuration.
 *
 * Same shape as `useIsTenantAdmin`, but backed by
 * `GET /api/v1/users/me/platform-roles` (which includes PLATFORM_ADMIN for
 * the caller's own identity) rather than `GET /api/v1/users/{id}/platform-roles`
 * (which deliberately excludes it for arbitrary other users).
 */
export function useIsPlatformAdmin() {
  const { data: currentUser } = useCurrentUser()

  const query = useQuery({
    queryKey: ["current-user-my-platform-roles", currentUser?.userId],
    queryFn: fetchMyPlatformRoles,
    enabled: !!currentUser?.userId,
    retry: false,
  })

  return {
    ...query,
    isPlatformAdmin: query.data?.includes("PLATFORM_ADMIN") ?? false,
  }
}
