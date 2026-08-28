import type { AssignedUserStatus } from "@/components/roles/types"

/**
 * A DMS user. Mirrors `RoleAssignedUser` (`components/roles/types.ts`) plus
 * the fields Role Details doesn't need — email and tenant — so the same
 * person shows up consistently across Role Details' assigned-users table
 * and User Access Summary. Sourced from Keycloak's admin user list
 * (`GET /admin/realms/{realm}/users`) in a real integration.
 */
export interface UserRecord {
  id: string
  name: string
  username: string
  email: string
  tenant: string
  status: AssignedUserStatus
}
