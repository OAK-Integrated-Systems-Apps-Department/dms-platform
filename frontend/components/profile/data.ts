import type { ProfileSupplement } from "./types"

/**
 * Placeholder for the fields Keycloak's Account REST API would return
 * alongside `useCurrentUser()` — swap for a real fetch/query hook once
 * that endpoint is wired up. See the caveat in `./types`.
 */
export const profileSupplement: ProfileSupplement = {
  roles: ["Compliance Officer"],
  lastLogin: "2026-08-27 09:14",
}