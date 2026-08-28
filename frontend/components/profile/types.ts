/**
 * Fields shown on My Profile that aren't part of `CurrentUser`
 * (`lib/auth/use-current-user.ts`) — that hook mirrors dms-core's
 * `/api/v1/users/me`, which only returns userId/email/displayName/status.
 *
 * Tenant, role(s), and account timestamps live on a fuller profile source —
 * per the wireframe, that's Keycloak's Account REST API
 * (`/realms/{realm}/account/`), not the token-scoped `userinfo` claims used
 * elsewhere. Until that's wired up, `profileSupplement` in `./data` mocks
 * this shape.
 */
export interface ProfileSupplement {
  tenant: string
  roles: string[]
  accountCreated: string
  lastLogin: string
}
