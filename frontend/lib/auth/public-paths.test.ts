import { describe, expect, it } from "vitest"

import { isPublicPath } from "./public-paths"

describe("isPublicPath - the public pages", () => {
  it.each(["/login", "/login-timeout", "/reset-password", "/update-password", "/error", "/session-expired"])("%s is public", (path) => {
    expect(isPublicPath(path)).toBe(true)
  })

  it("the portal and everything under it is public", () => {
    for (const path of ["/portal", "/portal/accept", "/portal/files", "/portal/a/b/c", "/portal/"]) {
      expect(isPublicPath(path)).toBe(true)
    }
  })

  it("a share link is public", () => {
    for (const path of ["/s/abc123", "/s/a", "/s/9f8e7d6c-token", "/s/abc/extra"]) {
      expect(isPublicPath(path)).toBe(true)
    }
  })
})

describe("isPublicPath - look-alikes are protected", () => {
  it.each([
    "/loginfoo",
    "/login-anything",
    "/login-timeoutx",
    "/login-timeout-x",
    "/error-foo",
    "/errors",
    "/reset-passwordx",
    "/reset-password-now",
    "/update-passwordx",
    "/session-expiredx",
    "/session-expired-x",
    "/portalx",
    "/portal-admin",
    "/portals",
    "/shared",
    "/sx",
    "/s-admin",
    "/sa/b",
  ])("%s is not public", (path) => {
    expect(isPublicPath(path)).toBe(false)
  })

  it("nothing may follow a standalone public page", () => {
    for (const path of ["/login/x", "/login/anything", "/error/x", "/reset-password/step", "/update-password/x", "/session-expired/x", "/login-timeout/x"]) {
      expect(isPublicPath(path)).toBe(false)
    }
  })

  it("bare /s and /s/ (no token) are not public", () => {
    expect(isPublicPath("/s")).toBe(false)
    expect(isPublicPath("/s/")).toBe(false)
  })

  it("the application's protected routes are protected", () => {
    for (const path of ["/", "/documents", "/documents/123", "/admin/users", "/admin/sharing", "/monitoring", "/monitoring/panels/d-solo/x", "/profile", "/tasks", "/shared", "/help"]) {
      expect(isPublicPath(path)).toBe(false)
    }
  })
})

describe("isPublicPath - the path is taken exactly as the proxy receives it", () => {
  it("is case-sensitive, like Next's routes", () => {
    for (const path of ["/LOGIN", "/Login", "/PORTAL", "/Portal/accept", "/S/abc", "/Error"]) {
      expect(isPublicPath(path)).toBe(false)
    }
  })

  it("does not decode: an encoded look-alike of a public page is not public (the safe side)", () => {
    for (const path of ["/%6Cogin", "/log%69n", "/login%2Ffoo", "/portal%2Fx", "/s%2Fabc", "/%2Fportal"]) {
      expect(isPublicPath(path)).toBe(false)
    }
  })

  it("does not normalise dot segments or repeated slashes - Next does that before the proxy, so it never receives them", () => {
    // Pure string rule, pinned so nobody adds a second, divergent normaliser here:
    expect(isPublicPath("/login/../documents")).toBe(false) // not an exact page, not under a public section
    expect(isPublicPath("//login")).toBe(false)
    expect(isPublicPath("///documents")).toBe(false)
    // ...and "/portal/../documents" still begins with the public section, which is exactly why this helper must only ever
    // see a path Next has already resolved (production build: it is redirected/resolved to "/documents" first).
    expect(isPublicPath("/portal/../documents")).toBe(true)
  })

  it("a query string is not part of a pathname, so it cannot make a page public", () => {
    // The proxy passes `request.nextUrl.pathname`; a string that still contains "?" is not any public page.
    expect(isPublicPath("/loginfoo?x=/login")).toBe(false)
    expect(isPublicPath("/documents?next=/portal")).toBe(false)
    expect(isPublicPath("/login?x=1")).toBe(false)
  })

  it("empty and odd input is protected", () => {
    for (const path of ["", " ", "login", "portal", "s/abc", "/ login"]) {
      expect(isPublicPath(path)).toBe(false)
    }
  })
})