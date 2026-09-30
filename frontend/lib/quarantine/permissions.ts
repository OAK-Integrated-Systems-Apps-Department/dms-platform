import { PermissionCodes } from "@/lib/auth/permission-codes"

/** Any single quarantine capability gets you onto the Quarantine screens - same rule as the sidebar item and the backend's list/get endpoints. */
export const QUARANTINE_ANY_PERMISSION = [
  PermissionCodes.DOCM_QUARANTINE_VIEW,
  PermissionCodes.DOCM_QUARANTINE_RELEASE,
  PermissionCodes.DOCM_QUARANTINE_DELETE,
  PermissionCodes.DOCM_QUARANTINE_OVERRIDE,
]
