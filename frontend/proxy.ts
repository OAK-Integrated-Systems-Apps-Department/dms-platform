// app/../proxy.ts  (same location as the old middleware.ts, at the frontend root alongside app/)
import { NextResponse, type NextRequest } from "next/server"

import { ACCESS_TOKEN_COOKIE, REFRESH_TOKEN_COOKIE } from "@/lib/auth/cookie-names"

const PUBLIC_PATHS = ["/login", "/reset-password", "/update-password", "/error", "/login-timeout", "/session-expired"]

// Backend is wired up: dms-core sets the dms_access_token cookie on a
// successful /api/v1/auth/login call (see AuthCookieService), so route
// gating can now rely on it instead of bypassing auth entirely.
const DEV_BYPASS_AUTH = false

export function proxy(request: NextRequest) {
  if (DEV_BYPASS_AUTH) {
    return NextResponse.next()
  }

  const { pathname } = request.nextUrl
  const isPublicPath = PUBLIC_PATHS.some((path) => pathname.startsWith(path))
  const hasSession = Boolean(request.cookies.get(ACCESS_TOKEN_COOKIE)?.value)
  // The access cookie only lives 30 minutes, so after a browser restart a
  // "Remember me" visitor has just the refresh cookie. Let them through to the
  // app: its first API call gets a 401, renews the session from that cookie and
  // retries (lib/api-fetch.ts); if the cookie is stale the backend clears it and
  // the app sends them to /session-expired.
  const hasRefreshToken = Boolean(request.cookies.get(REFRESH_TOKEN_COOKIE)?.value)

  if (!hasSession && !hasRefreshToken && !isPublicPath) {
    const loginUrl = new URL("/login", request.url)
    loginUrl.searchParams.set("from", pathname)
    return NextResponse.redirect(loginUrl)
  }

  if (hasSession && pathname === "/login") {
    return NextResponse.redirect(new URL("/", request.url))
  }

  return NextResponse.next()
}

export const config = {
  // Run on everything except static assets and images. There's no local
  // /api to exclude anymore — every data call goes straight to your Java
  // backend (see lib/api-config.ts) — but /login etc. still need to stay
  // reachable while logged out, which PUBLIC_PATHS above handles.
  matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"],
}