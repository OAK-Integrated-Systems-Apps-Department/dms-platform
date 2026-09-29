/**
 * Fields shown on My Profile that aren't part of `CurrentUser`
 * (`lib/auth/use-current-user.ts`) — that hook mirrors dms-core's
 * `/api/v1/users/me`, which has no concept of role assignments or login
 * history at all (tenant and account-created date DO come from `/me`
 * now — see `tenantId`/`createdAt` on `CurrentUser`).
 *
 * Role(s) and Last Login live on a fuller profile source — per the
 * wireframe, that's Keycloak's Account REST API
 * (`/realms/{realm}/account/`), not the token-scoped `userinfo` claims
 * used elsewhere. Until that's wired up, `profileSupplement` in `./data`
 * mocks this shape.
 */
export interface ProfileSupplement {
  roles: string[]
  lastLogin: string
}