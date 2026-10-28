"use client"

import { useQuery } from "@tanstack/react-query"

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
 *
 * Deliberately does NOT wait on `useCurrentUser`. It used to be gated on
 * `enabled: !!currentUser?.userId`, which made it permanently broken for the
 * only accounts it exists to identify: a platform administrator belongs to no
 * tenant, so `GET /api/v1/users/me` answers 401 TENANT_CONTEXT_MISSING for
 * them, `currentUser` was never set, the query never ran, and `isPending`
 * stayed true for the rest of the session - leaving Organisations stuck on
 * "Checking your access…" and every platform-admin nav item hidden.
 *
 * The endpoint reads the caller's own JWT and needs no tenant context, so it
 * is safe to run unconditionally - the same way `useCurrentUser` itself does.
 */
export function useIsPlatformAdmin() {
  const query = useQuery({
    queryKey: ["current-user-my-platform-roles"],
    queryFn: fetchMyPlatformRoles,
    retry: false,
  })

  return {
    ...query,
    isPlatformAdmin: query.data?.includes("PLATFORM_ADMIN") ?? false,
  }
}
