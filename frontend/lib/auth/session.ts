import type { NextResponse } from "next/server"

import type { TokenSet } from "./keycloak"
import { ACCESS_TOKEN_COOKIE, REFRESH_TOKEN_COOKIE } from "./cookie-names"

export { ACCESS_TOKEN_COOKIE, REFRESH_TOKEN_COOKIE }

/**
 * Writes both tokens onto a NextResponse as httpOnly cookies. httpOnly so
 * client-side JS (and any XSS) can never read the raw tokens — the
 * browser just carries them automatically on same-origin requests.
 */
export function setSessionCookies(response: NextResponse, tokens: TokenSet): NextResponse {
  const isProd = process.env.NODE_ENV === "production"

  response.cookies.set(ACCESS_TOKEN_COOKIE, tokens.accessToken, {
    httpOnly: true,
    secure: isProd,
    sameSite: "lax",
    path: "/",
    maxAge: tokens.expiresIn,
  })
  response.cookies.set(REFRESH_TOKEN_COOKIE, tokens.refreshToken, {
    httpOnly: true,
    secure: isProd,
    sameSite: "lax",
    path: "/",
    maxAge: tokens.refreshExpiresIn,
  })
  return response
}

export function clearSessionCookies(response: NextResponse): NextResponse {
  response.cookies.delete(ACCESS_TOKEN_COOKIE)
  response.cookies.delete(REFRESH_TOKEN_COOKIE)
  return response
}

/**
 * Decodes the (already-verified-by-Keycloak) JWT payload for display
 * purposes only — e.g. showing the signed-in user's name in the sidebar.
 * This is NOT signature verification; the backend (dms-core) is the one
 * that verifies the token on every API call per
 * backend/dms-core/.../security/SecurityConfig.java. Never make an
 * authorization decision in the frontend based on this alone.
 */
export function decodeAccessTokenClaims(accessToken: string): Record<string, unknown> | null {
  try {
    const payload = accessToken.split(".")[1]
    const json = Buffer.from(payload, "base64url").toString("utf-8")
    return JSON.parse(json)
  } catch {
    return null
  }
}