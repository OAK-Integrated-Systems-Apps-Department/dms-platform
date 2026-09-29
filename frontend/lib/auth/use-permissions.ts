"use client"

import { useCurrentUser } from "@/lib/auth/use-current-user"

/**
 * The signed-in user's resolved permission codes (e.g. "user.view",
 * "role.manage"), fetched once as part of `GET /api/v1/users/me` (see
 * `useCurrentUser`) and exposed here as the single source of truth for
 * every permission-gated control across the admin screens (Users, Roles,
 * Role Permission Configuration, etc).
 *
 * This exists because the Keycloak-issued JWT the frontend receives
 * carries only the caller's tenant-defined *role name* (e.g. "KP") under
 * `realm_access.roles` — since roles are tenant-defined and dynamic, that
 * name carries no decodable permission information on its own. The
 * backend already resolves "role name -> granted permissions" for every
 * `@PreAuthorize` check it enforces (see `AuthorizationService`); this
 * hook surfaces that same resolved set to the frontend so its UI can
 * match what the backend will actually allow, instead of guessing from
 * the role name or (worse) just always rendering the control and letting
 * a 403 toast be the first the user hears of it.
 *
 * Two special cases the resolved permission list intentionally does NOT
 * cover, since neither is a data-driven permission the DB tracks — check
 * these separately alongside `hasPermission()` where relevant:
 * - `useIsTenantAdmin()` — the User List's permanent-delete action is
 *   TENANT_ADMIN-only (`@authz.isTenantAdmin()`), not permission-code-gated.
 * - `useIsPlatformAdmin()` — cross-tenant platform-admin screens.
 *
 * While `useCurrentUser`'s query is loading, `permissions` is `[]` and
 * `hasPermission` returns `false` for everything — every gated control
 * stays hidden rather than flashing visible-then-hidden once the real
 * list arrives. Callers that need to distinguish "still loading" from
 * "genuinely has no permissions" can read `isLoading` off the return
 * value.
 */
export function usePermissions() {
  const { data: currentUser, isLoading } = useCurrentUser()
  const permissions = currentUser?.permissions ?? []

  function hasPermission(code: string): boolean {
    return permissions.includes(code)
  }

  function hasAnyPermission(...codes: string[]): boolean {
    return codes.some(hasPermission)
  }

  return { permissions, hasPermission, hasAnyPermission, isLoading }
}
