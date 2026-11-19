import { PermissionCodes } from "@/lib/auth/permission-codes"

/**
 * The permissions that let someone open a user's own pages (View User, Activity, Sessions, MFA, Access Summary) -
 * "any of" these, exactly the set dms-core accepts on `GET /api/v1/users/{userId}` (`UserController#getUser`'s
 * `@PreAuthorize`, which is what every one of those pages loads the user with). Kept in one place so the pages cannot
 * drift apart from each other or from the backend (FE-M4).
 *
 * `OTUA_USERS_CREATE` is deliberately not in it: the backend does not accept it on that endpoint.
 *
 * Typed `string[]`, not a read-only tuple, because `PermissionGate` takes `string | string[]`.
 */
export const USER_VIEW_PERMISSIONS: string[] = [
  PermissionCodes.OTUA_USERS_VIEW,
  PermissionCodes.OTUA_USERS_UPDATE,
  PermissionCodes.OTUA_USERS_RESET_PASSWORD,
  PermissionCodes.OTUA_USERS_ACTIVATE,
  PermissionCodes.OTUA_USERS_DEACTIVATE,
  PermissionCodes.OTUA_USERS_DELETE,
  PermissionCodes.OTUA_USERS_ASSIGN_ROLE,
]