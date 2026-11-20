// @vitest-environment node
import { NextRequest } from "next/server"
import { describe, expect, it } from "vitest"

import { proxy } from "./proxy"

const ORIGIN = "http://dms.test"
const ACCESS = "dms_access_token=a.b.c"
const REFRESH = "dms_refresh_token=r.r.r"

function run(path: string, cookies: string[] = []) {
  return proxy(new NextRequest(`${ORIGIN}${path}`, { headers: cookies.length ? { cookie: cookies.join("; ") } : {} }))
}

const passesThrough = (response: Response) => response.status === 200 && response.headers.get("x-middleware-next") === "1"
const redirectsTo = (response: Response, location: string) => response.status === 307 && response.headers.get("location") === `${ORIGIN}${location}`

describe("proxy - /login", () => {
  it("no cookies: the login page is shown", () => {
    expect(passesThrough(run("/login"))).toBe(true)
  })

  it("access cookie only: bounced to / (a signed-in person typing /login)", () => {
    expect(redirectsTo(run("/login", [ACCESS]), "/")).toBe(true)
  })

  it("refresh cookie only: the login page is shown", () => {
    expect(passesThrough(run("/login", [REFRESH]))).toBe(true)
  })

  it("both cookies: bounced to /", () => {
    expect(redirectsTo(run("/login", [ACCESS, REFRESH]), "/")).toBe(true)
  })

  it("access cookie and /login?expired=1: the form is shown instead of looping (FE-M3-B)", () => {
    expect(passesThrough(run("/login?expired=1", [ACCESS]))).toBe(true)
    expect(passesThrough(run("/login?expired=1", [ACCESS, REFRESH]))).toBe(true)
  })

  it("only the exact flag counts - a look-alike is bounced like a plain /login", () => {
    for (const query of ["expired=0", "expired=true", "expired=", "EXPIRED=1", "reason=expired"]) {
      expect(redirectsTo(run(`/login?${query}`, [ACCESS]), "/")).toBe(true)
    }
  })

  it("the flag creates no destination: a hostile from stays a value for the login page to validate, and nothing redirects", () => {
    const response = run("/login?expired=1&from=https%3A%2F%2Fevil.example", [ACCESS])
    expect(passesThrough(response)).toBe(true)
    expect(response.headers.get("location")).toBeNull()
  })

  it("the bounce itself cannot be steered off-site", () => {
    const response = run("/login?from=https%3A%2F%2Fevil.example", [ACCESS])
    expect(redirectsTo(response, "/")).toBe(true)
  })

  it("only /login is relaxed: /login/other-path-style routes keep their behaviour", () => {
    expect(passesThrough(run("/login-timeout", [ACCESS]))).toBe(true)
    expect(passesThrough(run("/session-expired", [ACCESS]))).toBe(true)
  })
})

describe("proxy - protected routes stay protected", () => {
  it("no cookies: redirected to /login with the path as from", () => {
    expect(redirectsTo(run("/documents"), "/login?from=%2Fdocuments")).toBe(true)
    expect(redirectsTo(run("/"), "/login?from=%2F")).toBe(true)
  })

  it("the sign-in-again flag does not open anything else", () => {
    expect(redirectsTo(run("/documents?expired=1"), "/login?from=%2Fdocuments")).toBe(true)
    expect(redirectsTo(run("/admin/users?expired=1"), "/login?from=%2Fadmin%2Fusers")).toBe(true)
  })

  it("a refresh cookie alone lets the app renew the session", () => {
    expect(passesThrough(run("/documents", [REFRESH]))).toBe(true)
  })

  it("an access cookie lets the request through (dms-core validates it, not the proxy)", () => {
    expect(passesThrough(run("/documents", [ACCESS]))).toBe(true)
  })
})

describe("proxy - public routes (the external-sharing developer's paths) are unchanged", () => {
  it.each(["/s/abc123", "/portal", "/portal/files", "/reset-password", "/update-password", "/error", "/session-expired", "/login-timeout"])(
    "%s needs no session",
    (path) => {
      expect(passesThrough(run(path))).toBe(true)
      expect(passesThrough(run(path, [ACCESS, REFRESH]))).toBe(true)
    },
  )
})

describe("proxy - look-alikes of public paths are no longer public (FE-L1)", () => {
  const LOOK_ALIKES = ["/loginfoo", "/login-anything", "/error-foo", "/errors", "/reset-passwordx", "/session-expiredx", "/portalx", "/portal-admin", "/login%2Ffoo", "/s", "/sx"]

  it.each(LOOK_ALIKES)("signed out: %s is redirected to the sign-in page, with the path as from", (path) => {
    const response = run(path)
    expect(response.status).toBe(307)
    expect(response.headers.get("location")).toBe(`${ORIGIN}/login?from=${encodeURIComponent(path)}`)
  })

  it.each(LOOK_ALIKES)("with only a refresh cookie: %s is let through for the app to renew the session (as any protected route)", (path) => {
    expect(passesThrough(run(path, [REFRESH]))).toBe(true)
  })

  it.each(LOOK_ALIKES)("with an access cookie: %s is let through (dms-core, not the proxy, decides)", (path) => {
    expect(passesThrough(run(path, [ACCESS]))).toBe(true)
  })

  it("/login stays the only path that bounces a signed-in visitor to /, and /login?expired=1 still shows the form", () => {
    expect(redirectsTo(run("/login", [ACCESS]), "/")).toBe(true)
    expect(passesThrough(run("/login?expired=1", [ACCESS]))).toBe(true)
    expect(passesThrough(run("/loginfoo", [ACCESS]))).toBe(true)
  })

  it("/admin and /monitoring routes stay protected when signed out", () => {
    for (const path of ["/admin/users", "/admin/users/u1/activity", "/admin/sharing", "/monitoring", "/monitoring/panels/d-solo/x"]) {
      expect(redirectsTo(run(path), `/login?from=${encodeURIComponent(path)}`)).toBe(true)
    }
  })

  it("the public pages, the portal and share links stay open signed out", () => {
    for (const path of ["/login", "/login-timeout", "/reset-password", "/update-password", "/error", "/session-expired", "/portal", "/portal/accept", "/s/abc123"]) {
      expect(passesThrough(run(path))).toBe(true)
    }
  })

  it("a share link needs a token: bare /s/ is protected", () => {
    expect(redirectsTo(run("/s/"), "/login?from=%2Fs%2F")).toBe(true)
  })

  it("a query string cannot make a protected path public", () => {
    expect(redirectsTo(run("/documents?x=/login"), "/login?from=%2Fdocuments")).toBe(true)
    expect(redirectsTo(run("/loginfoo?expired=1"), "/login?from=%2Floginfoo")).toBe(true)
  })

  it("a signed-out visitor to /login keeps the FE-M1 from value (validated by the login page, not the proxy)", () => {
    expect(passesThrough(run("/login?from=%2Fdocuments"))).toBe(true)
    expect(passesThrough(run("/login?from=https%3A%2F%2Fevil.example"))).toBe(true)
  })
})