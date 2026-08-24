import { cookies } from "next/headers"
import { NextResponse } from "next/server"

import { ACCESS_TOKEN_COOKIE } from "@/lib/auth/cookie-names"

/**
 * DMS_CORE_URL should be reachable from wherever the Next.js *server*
 * process runs — same pattern as KEYCLOAK_URL in lib/auth/keycloak.ts.
 * http://localhost:8080 for `bun dev` against
 * infra/local/docker-compose.yml exposed ports, or the compose service
 * name (e.g. http://dms-core:8080) when the frontend itself is a
 * container on the same network.
 */
const DMS_CORE_URL = process.env.DMS_CORE_URL ?? "http://localhost:8080"

/**
 * Thin server-side proxy to dms-core's GET /api/v1/users/me. The browser
 * never sees the access token (it's an httpOnly cookie) — this route reads
 * it server-side, attaches it as a Bearer token, and forwards the backend's
 * response. Mirrors how app/api/auth/* already keeps Keycloak calls
 * server-only (see lib/auth/keycloak.ts).
 */
export async function GET() {
  const cookieStore = await cookies()
  const accessToken = cookieStore.get(ACCESS_TOKEN_COOKIE)?.value

  if (!accessToken) {
    return NextResponse.json({ error: "Not signed in." }, { status: 401 })
  }

  const response = await fetch(`${DMS_CORE_URL}/api/v1/users/me`, {
    headers: { Authorization: `Bearer ${accessToken}` },
    cache: "no-store",
  }).catch(() => null)

  if (!response) {
    return NextResponse.json({ error: "dms-core is unreachable." }, { status: 502 })
  }

  if (!response.ok) {
    const body = await response.json().catch(() => null)
    return NextResponse.json(
      { error: body?.message ?? "Could not load your profile." },
      { status: response.status }
    )
  }

  return NextResponse.json(await response.json())
}