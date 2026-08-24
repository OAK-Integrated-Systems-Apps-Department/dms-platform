import { NextResponse, type NextRequest } from "next/server"

import { ACCESS_TOKEN_COOKIE } from "@/lib/auth/cookie-names"

const PUBLIC_PATHS = ["/login", "/reset-password"]

export function middleware(request: NextRequest) {
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
  // Run on everything except static assets, images, and the API routes
  // (those handle their own auth — /api/auth/* must stay reachable while
  // logged out, since that's how you log in in the first place).
  matcher: ["/((?!api|_next/static|_next/image|favicon.ico).*)"],
}