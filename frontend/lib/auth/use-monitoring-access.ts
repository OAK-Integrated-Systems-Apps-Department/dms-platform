"use client"

import { PermissionCodes } from "@/lib/auth/permission-codes"
import { useIsPlatformAdmin } from "@/lib/auth/use-is-platform-admin"
import { usePermissions } from "@/lib/auth/use-permissions"

/**
 * What the caller may see on System Monitoring.
 *
 * A platform administrator always may, and a tenant admin's resolved permission
 * list already includes both codes. Anyone else needs them from a role their
 * tenant admin assigned. Logs are a grant of their own.
 *
 * `isPending` is true only while we can't yet say no: a platform admin has no
 * tenant, so /me never answers for them and must not keep this pending.
 */
export function useMonitoringAccess() {
  const { isPlatformAdmin, isPending: rolesPending } = useIsPlatformAdmin()
  const { hasPermission, isLoading: permissionsLoading } = usePermissions()

  const canView =
    isPlatformAdmin || hasPermission(PermissionCodes.SHAD_VIEW_SYSTEM_HEALTH)
  const canViewLogs =
    isPlatformAdmin || hasPermission(PermissionCodes.SHAD_VIEW_SYSTEM_LOGS)

  return {
    canView,
    canViewLogs,
    isPending: !canView && (rolesPending || permissionsLoading),
  }
}
