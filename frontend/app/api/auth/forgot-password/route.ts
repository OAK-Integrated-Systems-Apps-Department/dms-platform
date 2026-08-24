import { NextResponse } from "next/server"

/**
 * Same DMS_CORE_URL convention as app/api/users/route.ts.
 */
const DMS_CORE_URL = process.env.DMS_CORE_URL ?? "http://localhost:8080"

interface ForgotPasswordRequestBody {
  usernameOrEmail?: string
}

/**
 * Server-side proxy to dms-core's POST /api/v1/auth/forgot-password.
 *
 * Unlike /api/auth/login, this route needs no access token — the whole
 * point is that the caller is logged out and can't get in. It's reachable
 * while logged out for the same reason /api/auth/login is: middleware.ts
 * excludes all of /api/** from its session check.
 *
 * Deliberately enumeration-safe: this ALWAYS returns 200 with the same
 * generic body, whether or not the username/email matches an account,
 * and never surfaces dms-core's response body to the browser. The
 * distinguishing information (does this account exist, did the email
 * send) only ever goes to server logs, never to the client. If dms-core
 * is unreachable we still return the generic success — a network blip
 * shouldn't leak "your account doesn't exist" by contrast with the
 * happy path.
 */
export async function POST(request: Request) {
  const body = (await request.json().catch(() => ({}))) as ForgotPasswordRequestBody
  const usernameOrEmail = body.usernameOrEmail?.trim()

  const genericResponse = NextResponse.json({
    ok: true,
    message: "If an account matches, we've sent a link to reset the password.",
  })

  if (!usernameOrEmail) {
    // Still generic — an empty submission shouldn't behave differently
    // from a submission that just didn't match anyone.
    return genericResponse
  }

//   try {
//     await fetch(`${DMS_CORE_URL}/api/v1/auth/forgot-password`, {
//       method: "POST",
//       headers: { "Content-Type": "application/json" },
//       body: JSON.stringify({ usernameOrEmail }),
//       cache: "no-store",
//     })
//   } catch {
//     // Swallowed on purpose — see doc comment above.
//   }

    try {
    const response = await fetch(`${DMS_CORE_URL}/api/v1/auth/forgot-password`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ usernameOrEmail }),
        cache: "no-store",
    })

    if (!response.ok) {
        console.error(
        `Forgot-password request failed: ${response.status} ${response.statusText}`
        )
    }
    } catch (error) {
    console.error("Failed to contact dms-core:", error)
    }

  return genericResponse
}