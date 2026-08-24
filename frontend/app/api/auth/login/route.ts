import { NextResponse } from "next/server"

import { KeycloakAuthError, checkMfaRequired, loginWithPassword } from "@/lib/auth/keycloak"
import { setSessionCookies } from "@/lib/auth/session"

interface LoginRequestBody {
  username?: string
  password?: string
  /** Present once the login page's OTP step has been shown and filled in. */
  totp?: string
}

export async function POST(request: Request) {
  const body = (await request.json().catch(() => ({}))) as LoginRequestBody
  const username = body.username?.trim()
  const password = body.password
  const totp = body.totp?.trim()

  if (!username || !password) {
    return NextResponse.json(
      { ok: false, error: "Enter your username and password." },
      { status: 400 }
    )
  }

  // Step 1 only (no code submitted yet): find out whether this account has
  // OTP configured BEFORE spending an attempt against Keycloak's token
  // endpoint. This exists because Keycloak's direct-grant flow can't tell
  // a client "this needs an OTP code" as distinct from "wrong password" —
  // see the doc comment on loginWithPassword in lib/auth/keycloak.ts. If
  // we skipped this and just tried username+password, a correct password
  // on an OTP-enabled account would fail with the same generic error as a
  // wrong password, and the person would have no way to know they just
  // needed to enter a code.
  if (!totp) {
    const mfaRequired = await checkMfaRequired(username)
    if (mfaRequired) {
      return NextResponse.json({ ok: false, mfaRequired: true })
    }
  }

  try {
    const tokens = await loginWithPassword(username, password, totp)
    return setSessionCookies(NextResponse.json({ ok: true }), tokens)
  } catch (error) {
    if (error instanceof KeycloakAuthError) {
      // Matches the inline alert copy already in the login design
      // (dms-login.html) — deliberately generic so we don't reveal
      // whether the username exists, and the same message covers "wrong
      // password" and "wrong/expired OTP code" for the same reason.
      return NextResponse.json({ ok: false, error: error.message }, { status: 401 })
    }
    return NextResponse.json(
      { ok: false, error: "Something went wrong. Please try again." },
      { status: 502 }
    )
  }
}