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

/**
 * Password status shown on My Security Settings — `/me` has no
 * password-last-changed field, so this stays mocked; `mfaEnabled` DOES
 * come from `/me` now (see `CurrentUser`) and is no longer part of this
 * type. Real source for this remaining field would be Keycloak's Account
 * REST API — the actions themselves (changing password, managing MFA
 * methods) aren't DMS-built forms at all; they deep-link into Keycloak's
 * own Account Console. See `KEYCLOAK_ACCOUNT_URL` in `lib/api-config.ts`.
 */
export interface SecuritySupplement {
  passwordLastChanged: string
}