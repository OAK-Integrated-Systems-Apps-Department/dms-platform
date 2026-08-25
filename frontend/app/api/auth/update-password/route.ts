import { NextResponse } from "next/server"

const DMS_CORE_URL = process.env.DMS_CORE_URL ?? "http://localhost:8080"

interface UpdatePasswordRequestBody {
  token?: string
  newPassword?: string
}

/**
 * Server-side proxy to dms-core's POST /api/v1/auth/update-password.
 *
 * Reachable while logged out, same as /api/auth/forgot-password — the
 * person here just clicked an emailed link and has no session cookie yet.
 * Authorization comes entirely from `token` (the value carried in that
 * link's query string), not from any cookie.
 *
 * NOTE: this backend endpoint doesn't exist yet — how `token` gets minted
 * and verified is a separate piece we're deliberately not building right
 * now. This route just defines the contract the frontend expects:
 * { token, newPassword } in, and either { ok: true } or a 4xx with a
 * message the page can show inline.
 */
export async function POST(request: Request) {
  const body = (await request.json().catch(() => ({}))) as UpdatePasswordRequestBody
  const token = body.token?.trim()
  const newPassword = body.newPassword

  if (!token) {
    return NextResponse.json(
      { ok: false, error: "This link is missing or malformed. Request a new one." },
      { status: 400 }
    )
  }
  if (!newPassword || newPassword.length < 8) {
    return NextResponse.json(
      { ok: false, error: "Password must be at least 8 characters." },
      { status: 400 }
    )
  }

  const response = await fetch(`${DMS_CORE_URL}/api/v1/auth/update-password`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ token, newPassword }),
    cache: "no-store",
  }).catch(() => null)

  if (!response) {
    return NextResponse.json({ ok: false, error: "Something went wrong. Please try again." }, { status: 502 })
  }

  if (!response.ok) {
    const errorBody = (await response.json().catch(() => null)) as { message?: string } | null
    const fallback =
      response.status === 410
        ? "This link has expired. Request a new one."
        : "This link is invalid. Request a new one."
    return NextResponse.json({ ok: false, error: errorBody?.message ?? fallback }, { status: response.status })
  }

  return NextResponse.json({ ok: true })
}