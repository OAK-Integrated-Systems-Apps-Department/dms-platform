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
 * Password status shown in My Profile's merged Password/MFA sections
 * (`SecurityOverview`, formerly the standalone `/profile/security` page)
 * — `/me` has no password-last-changed field, so this stays mocked;
 * `mfaEnabled` DOES come from `/me` now (see `CurrentUser`) and is no
 * longer part of this type. Real source for this remaining field would be
 * Keycloak's Account REST API. Unlike MFA enrollment ("Manage MFA
 * methods", still a deep link into Keycloak's own Account Console — see
 * `KEYCLOAK_ACCOUNT_URL` in `lib/api-config.ts`), "Change password" is a
 * DMS-built self-service flow: `requestPasswordReset`
 * (`lib/auth/request-password-reset.ts`), the same one `/reset-password`
 * uses.
 */
export interface SecuritySupplement {
  passwordLastChanged: string
}