import type { ProfileSupplement } from "./types"

/**
 * Placeholder for the fields Keycloak's Account REST API would return
 * alongside `useCurrentUser()` — swap for a real fetch/query hook once
 * that endpoint is wired up. See the caveat in `./types`.
 */
export const profileSupplement: ProfileSupplement = {
  tenant: "Acme Textiles",
  roles: ["Compliance Officer"],
  accountCreated: "2025-02-11",
  lastLogin: "2026-08-27 09:14",
}
