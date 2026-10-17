"use client"

import { ApiError } from "@/lib/api-error"
import { useCurrentUser } from "@/lib/auth/use-current-user"

/**
 * Which home dashboard this account gets - and, just as importantly,
 * whether that is known yet.
 *
 * There are two home dashboards. The ordinary one is tenant-scoped
 * (documents, folders, activity); a platform administrator belongs to no
 * tenant, so every one of those calls answers 401 TENANT_CONTEXT_MISSING
 * for them and they get the cross-tenant view instead.
 *
 * Which one applies can only be known once `GET /api/v1/users/me` has
 * answered. The page used to decide from the error alone:
 *
 *     const noTenant = error instanceof ApiError && error.code === "..."
 *
 * While that call was still in flight there was no error yet, so `noTenant`
 * was false and a platform admin was shown the tenant dashboard - which then
 * fired its own tenant-scoped requests, collected its own 401s, and was
 * replaced a moment later when the error finally arrived. On every reload,
 * the wrong dashboard flashed up first.
 *
 * So the pending state is returned explicitly rather than being collapsed
 * into "tenant". A caller that cannot yet say which dashboard applies should
 * show neither.
 *
 * Keyed off the error rather than off the PLATFORM_ADMIN role deliberately:
 * the question is "can the tenant dashboard serve this account at all",
 * and the account that cannot be served is exactly the account that gets a
 * tenant-context failure - no more, no fewer.
 */
export function useDashboardScope() {
  const { isPending, error } = useCurrentUser()

  const isPlatform =
    error instanceof ApiError && error.code === "TENANT_CONTEXT_MISSING"

  return {
    /** Still waiting on `/users/me` - render neither dashboard yet. */
    isPending,
    /** The cross-tenant view: this account has no tenant to scope to. */
    isPlatform,
    /**
     * The ordinary tenant dashboard. False while pending, so tenant-scoped
     * queries can be gated on it and stay idle until the answer is in.
     * Any other failure (network, 500) still lands here, so the tenant
     * dashboard shows its own error rather than silently becoming the
     * platform one.
     */
    isTenant: !isPending && !isPlatform,
  }
}
