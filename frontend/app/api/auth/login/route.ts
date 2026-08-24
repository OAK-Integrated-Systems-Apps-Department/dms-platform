import { NextResponse } from "next/server"

import { KeycloakAuthError, loginWithPassword } from "@/lib/auth/keycloak"
import { setSessionCookies } from "@/lib/auth/session"

interface LoginRequestBody {
  username?: string
  password?: string
}

export async function POST(request: Request) {
  const body = (await request.json().catch(() => ({}))) as LoginRequestBody
  const username = body.username?.trim()
  const password = body.password

  if (!username || !password) {
    return NextResponse.json(
      { ok: false, error: "Enter your username and password." },
      { status: 400 }
    )
  }

  try {
    const tokens = await loginWithPassword(username, password)
    return setSessionCookies(NextResponse.json({ ok: true }), tokens)
  } catch (error) {
    if (error instanceof KeycloakAuthError) {
      // Matches the inline alert copy already in the login design
      // (dms-login.html) — deliberately generic so we don't reveal
      // whether the username exists.
      return NextResponse.json({ ok: false, error: error.message }, { status: 401 })
    }
    return NextResponse.json(
      { ok: false, error: "Something went wrong. Please try again." },
      { status: 502 }
    )
  }
}