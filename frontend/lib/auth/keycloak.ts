/**
 * All Keycloak network calls happen here, server-side only — this module
 * is only ever imported from route handlers (app/api/auth/**), never from
 * a "use client" component, so it's never bundled for the browser. The browser
 * never talks to Keycloak directly and never sees the raw tokens — it only
 * ever calls our own /api/auth/* routes, which call these functions and
 * hand back httpOnly cookies. See lib/auth/session.ts for the cookie side.
 *
 * KEYCLOAK_URL should be reachable from wherever the Next.js *server*
 * process runs: http://localhost:8081 for `bun dev` against
 * infra/local/docker-compose.yml exposed ports, or http://keycloak:8080
 * when the frontend itself is also a container on the same compose
 * network (see infra/local/docker-compose.yml's `frontend` service).
 */
const KEYCLOAK_URL = process.env.KEYCLOAK_URL ?? "http://localhost:8081"
const KEYCLOAK_REALM = process.env.KEYCLOAK_REALM ?? "oak-dms"
const KEYCLOAK_CLIENT_ID = process.env.KEYCLOAK_CLIENT_ID ?? "oak-dms-web"

const TOKEN_ENDPOINT = `${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/token`
const LOGOUT_ENDPOINT = `${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/logout`

export interface TokenSet {
  accessToken: string
  refreshToken: string
  /** Seconds until the access token expires, as reported by Keycloak. */
  expiresIn: number
  refreshExpiresIn: number
}

export class KeycloakAuthError extends Error {
  /** Keycloak's OAuth2 error code, e.g. "invalid_grant". */
  code: string
  constructor(code: string, message: string) {
    super(message)
    this.code = code
  }
}

function toTokenSet(body: {
  access_token: string
  refresh_token: string
  expires_in: number
  refresh_expires_in: number
}): TokenSet {
  return {
    accessToken: body.access_token,
    refreshToken: body.refresh_token,
    expiresIn: body.expires_in,
    refreshExpiresIn: body.refresh_expires_in,
  }
}

/**
 * Exchanges a username/password for a token set using the Direct Access
 * Grant (Resource Owner Password Credentials) flow. Requires
 * `directAccessGrantsEnabled: true` on the oak-dms-web client — see
 * infra/local/keycloak/oak-dms-realm.json.
 *
 * Throws KeycloakAuthError on bad credentials (never leaks Keycloak's raw
 * response — callers map this to the same "Invalid username or password"
 * copy used in the login design).
 */
export async function loginWithPassword(
  username: string,
  password: string,
  totp?: string
): Promise<TokenSet> {
  const params = new URLSearchParams({
    grant_type: "password",
    client_id: KEYCLOAK_CLIENT_ID,
    username,
    password,
    scope: "openid",
  })
  // Keycloak's direct-grant OTP validator reads this as a plain form
  // field alongside username/password in the SAME request — there's no
  // separate "submit the code" request. Omitted entirely (not sent as
  // "") when the caller has no code yet, since an empty string and a
  // present-but-wrong code are not the same thing to the validator.
  if (totp) {
    params.set("totp", totp)
  }

  const response = await fetch(TOKEN_ENDPOINT, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: params,
    cache: "no-store",
  })

  if (!response.ok) {
    const errorBody = (await response.json().catch(() => null)) as { error?: string } | null
    throw new KeycloakAuthError(
      errorBody?.error ?? "unknown_error",
      "Invalid username or password."
    )
  }

  return toTokenSet(await response.json())
}

const DMS_CORE_URL = process.env.DMS_CORE_URL ?? "http://localhost:8080"

/**
 * Asks dms-core's public AuthController whether this username has OTP
 * configured, so the login form knows to show the code field BEFORE
 * attempting the actual password grant. This is a UX hint only — it is
 * never trusted as an authorization decision, and a false answer (e.g.
 * dms-core unreachable) just means the OTP field stays hidden and, if the
 * account really does need a code, loginWithPassword() below fails with
 * the ordinary generic "Invalid username or password" error.
 */
export async function checkMfaRequired(usernameOrEmail: string): Promise<boolean> {
  try {
    const response = await fetch(
      `${DMS_CORE_URL}/api/v1/auth/mfa-required?username=${encodeURIComponent(usernameOrEmail)}`,
      { cache: "no-store" }
    )
    if (!response.ok) return false
    const body = (await response.json()) as { mfaRequired?: boolean }
    return body.mfaRequired ?? false
  } catch {
    return false
  }
}

/** Exchanges a still-valid refresh token for a fresh token set. */
export async function refreshTokenSet(refreshToken: string): Promise<TokenSet> {
  const response = await fetch(TOKEN_ENDPOINT, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "refresh_token",
      client_id: KEYCLOAK_CLIENT_ID,
      refresh_token: refreshToken,
    }),
    cache: "no-store",
  })

  if (!response.ok) {
    throw new KeycloakAuthError("invalid_grant", "Session could not be refreshed.")
  }

  return toTokenSet(await response.json())
}

/** Revokes the refresh token so it can't be used again after logout. */
export async function revokeRefreshToken(refreshToken: string): Promise<void> {
  await fetch(LOGOUT_ENDPOINT, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      client_id: KEYCLOAK_CLIENT_ID,
      refresh_token: refreshToken,
    }),
    cache: "no-store",
  }).catch(() => {
    // Best-effort — an unreachable Keycloak shouldn't block the user from
    // being logged out locally (their cookies are cleared regardless).
  })
}