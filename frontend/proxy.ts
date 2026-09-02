// app/../proxy.ts  (same location as the old middleware.ts, at the frontend root alongside app/)
import { NextResponse, type NextRequest } from "next/server"

import { ACCESS_TOKEN_COOKIE } from "@/lib/auth/cookie-names"

const PUBLIC_PATHS = ["/login", "/reset-password", "/update-password", "/error", "/login-timeout", "/session-expired"]

// TEMPORARY — no backend is wired up yet, so there's no way to ever get a
// dms_access_token cookie. Flip this back to false once your Java backend
// is connected and actually sets that cookie on login, or every route
// stays open regardless of session.
const DEV_BYPASS_AUTH = true

export function proxy(request: NextRequest) {
  if (DEV_BYPASS_AUTH) {
    return NextResponse.next()
  }

  const { pathname } = request.nextUrl
  const isPublicPath = PUBLIC_PATHS.some((path) => pathname.startsWith(path))
  const hasSession = Boolean(request.cookies.get(ACCESS_TOKEN_COOKIE)?.value)

  if (!hasSession && !isPublicPath) {
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