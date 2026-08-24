import { cookies } from "next/headers"
import { NextResponse } from "next/server"

import { revokeRefreshToken } from "@/lib/auth/keycloak"
import { clearSessionCookies, REFRESH_TOKEN_COOKIE } from "@/lib/auth/session"

export async function POST() {
  const cookieStore = await cookies()
  const refreshToken = cookieStore.get(REFRESH_TOKEN_COOKIE)?.value

  if (refreshToken) {
    await revokeRefreshToken(refreshToken)
  }

  return clearSessionCookies(NextResponse.json({ ok: true }))
}