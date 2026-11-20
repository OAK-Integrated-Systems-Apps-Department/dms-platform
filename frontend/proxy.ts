// app/../proxy.ts  (same location as the old middleware.ts, at the frontend root alongside app/)
import { NextResponse, type NextRequest } from "next/server"

import { ACCESS_TOKEN_COOKIE, REFRESH_TOKEN_COOKIE } from "@/lib/auth/cookie-names"
import { isPublicPath } from "@/lib/auth/public-paths"
import { isSignInAgainRequest } from "@/lib/auth/session-recovery"

// Backend is wired up: dms-core sets the dms_access_token cookie on a
// successful /api/v1/auth/login call (see AuthCookieService), so route
// gating can now rely on it instead of bypassing auth entirely.
const DEV_BYPASS_AUTH = false

export function proxy(request: NextRequest) {
  if (DEV_BYPASS_AUTH) {
    return NextResponse.next()
  }

  const { pathname } = request.nextUrl
  // Which paths a signed-out visitor may open - whole segments only, see lib/auth/public-paths.ts (FE-L1). "/s/<token>" (share
  // links) and "/portal" (guest sign-in and portal) are for people with no account at all (1.5).
  const isPublic = isPublicPath(pathname)
  const hasSession = Boolean(request.cookies.get(ACCESS_TOKEN_COOKIE)?.value)
  // The access cookie only lives 30 minutes, so after a browser restart a
  // "Remember me" visitor has just the refresh cookie. Let them through to the
  // app: its first API call gets a 401, renews the session from that cookie and
  // retries (lib/api-fetch.ts); if the cookie is stale the backend clears it and
  // the app sends them to /session-expired.
  const hasRefreshToken = Boolean(request.cookies.get(REFRESH_TOKEN_COOKIE)?.value)

  if (!hasSession && !hasRefreshToken && !isPublic) {
    const loginUrl = new URL("/login", request.url)
    loginUrl.searchParams.set("from", pathname)
    return NextResponse.redirect(loginUrl)
  }

  // Except for the deliberate "Sign in again" from /session-expired: the access cookie may be one dms-core no longer
  // accepts, and bouncing that person to "/" only sends them round the loop again (FE-M3-B). See lib/auth/session-recovery.ts.
  if (hasSession && pathname === "/login" && !isSignInAgainRequest(request.nextUrl.searchParams)) {
    return NextResponse.redirect(new URL("/", request.url))
  }

  return NextResponse.next()
}

export const config = {
  // Run on everything except static assets and images (public/images/ too: the
  // login screen shows one - and the tab icons, which a signed-out page needs too, and a signed-out request would otherwise be redirected
  // to /login and come back as HTML). There's no local
  // /api to exclude anymore — every data call goes straight to your Java
  // backend (see lib/api-config.ts) — but /login etc. still need to stay
  // reachable while logged out, which isPublicPath (lib/auth/public-paths.ts) handles.
  matcher: ["/((?!_next/static|_next/image|favicon.ico|icon.png|apple-icon.png|images/).*)"],
}