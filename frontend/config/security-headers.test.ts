import { describe, expect, it } from "vitest"

import nextConfig from "../next.config"
import {
  PERMISSIONS_POLICY,
  baseSecurityHeaders,
  contentSecurityPolicy,
  originOf,
  parseOrigins,
  reportOnlyPolicyHeader,
  securityHeaderRules,
} from "./security-headers"

const production = { development: false }

function directive(policy: string, name: string): string[] {
  const found = policy.split(";").map((d) => d.trim()).find((d) => d.startsWith(`${name} `))
  return found ? found.split(/\s+/).slice(1) : []
}

describe("base security headers", () => {
  const headers = Object.fromEntries(baseSecurityHeaders().map((h) => [h.key, h.value]))

  it("frames only from the same origin - not DENY, the monitoring page frames its own panels route", () => {
    expect(headers["X-Frame-Options"]).toBe("SAMEORIGIN")
  })

  it("stops MIME sniffing", () => {
    expect(headers["X-Content-Type-Options"]).toBe("nosniff")
  })

  it("sends only the origin to other sites", () => {
    expect(headers["Referrer-Policy"]).toBe("strict-origin-when-cross-origin")
  })

  it("turns off unused capabilities and leaves clipboard and fullscreen alone (the editor frame needs them)", () => {
    expect(headers["Permissions-Policy"]).toBe(PERMISSIONS_POLICY)
    for (const unused of ["camera", "microphone", "geolocation", "payment", "usb", "display-capture"]) {
      expect(PERMISSIONS_POLICY).toContain(`${unused}=()`)
    }
    expect(PERMISSIONS_POLICY).not.toMatch(/clipboard|fullscreen/)
  })

  it("does not set HSTS (the documented deployment is plain HTTP) or cross-origin isolation headers", () => {
    const keys = baseSecurityHeaders().map((h) => h.key.toLowerCase())
    expect(keys).not.toContain("strict-transport-security")
    expect(keys.some((k) => k.startsWith("cross-origin-"))).toBe(false)
  })
})

describe("report-only Content-Security-Policy", () => {
  it("is the report-only header and never the enforcing one", () => {
    const header = reportOnlyPolicyHeader({ NODE_ENV: "production" })
    expect(header.key).toBe("Content-Security-Policy-Report-Only")
    expect(baseSecurityHeaders().map((h) => h.key)).not.toContain("Content-Security-Policy")
  })

  it("omits frame-ancestors (ignored in report-only; X-Frame-Options does that job)", () => {
    expect(contentSecurityPolicy(production)).not.toContain("frame-ancestors")
  })

  it("allows only the app's own origin, blob: and data: where the app needs them - no external origin, no wildcard", () => {
    const policy = contentSecurityPolicy(production)
    expect(policy).not.toMatch(/https?:/)
    expect(policy).not.toContain("*")
    expect(directive(policy, "default-src")).toEqual(["'self'"])
    expect(directive(policy, "img-src")).toEqual(["'self'", "blob:", "data:"])
    expect(directive(policy, "media-src")).toEqual(["'self'", "blob:"])
    expect(directive(policy, "frame-src")).toEqual(["'self'", "blob:"])
    expect(directive(policy, "font-src")).toEqual(["'self'"])
    expect(directive(policy, "connect-src")).toEqual(["'self'"])
    expect(directive(policy, "form-action")).toEqual(["'self'"])
    expect(directive(policy, "base-uri")).toEqual(["'self'"])
    expect(directive(policy, "object-src")).toEqual(["'none'"])
  })

  it("has no unsafe-eval in production, and no nonce machinery", () => {
    const policy = contentSecurityPolicy(production)
    expect(policy).not.toContain("unsafe-eval")
    expect(policy).not.toContain("nonce-")
    expect(directive(policy, "script-src")).toEqual(["'self'", "'unsafe-inline'"])
  })

  it("adds unsafe-eval in development only", () => {
    expect(directive(contentSecurityPolicy({ development: true }), "script-src")).toContain("'unsafe-eval'")
  })

  it("adds the API origin to connect-src only when the build points at another origin", () => {
    expect(directive(contentSecurityPolicy({ ...production, apiBaseUrl: "http://localhost:8080" }), "connect-src"))
      .toEqual(["'self'", "http://localhost:8080"])
    expect(directive(contentSecurityPolicy({ ...production, apiBaseUrl: "" }), "connect-src")).toEqual(["'self'"])
  })

  it("adds configured editor origins to frame-src and form-action, nothing else", () => {
    const policy = contentSecurityPolicy({ ...production, extraOrigins: ["https://editor.test"] })
    expect(directive(policy, "frame-src")).toEqual(["'self'", "blob:", "https://editor.test"])
    expect(directive(policy, "form-action")).toEqual(["'self'", "https://editor.test"])
    expect(directive(policy, "script-src")).not.toContain("https://editor.test")
    expect(directive(policy, "connect-src")).not.toContain("https://editor.test")
  })
})

describe("configured origins", () => {
  it("accepts plain http(s) origins, space or comma separated, and drops anything else", () => {
    expect(parseOrigins("https://a.test, http://10.0.0.5:9980 https://a.test/path")).toEqual([
      "https://a.test",
      "http://10.0.0.5:9980",
    ])
    expect(parseOrigins("*, *.a.test, javascript:alert(1), data:text/html,x, ftp://a.test, https://u:p@a.test, nonsense, '")).toEqual([])
    expect(parseOrigins(undefined)).toEqual([])
    expect(parseOrigins("")).toEqual([])
  })

  it("derives the API origin from an absolute base URL only", () => {
    expect(originOf("http://localhost:8080")).toBe("http://localhost:8080")
    expect(originOf("")).toBeUndefined()
    expect(originOf(undefined)).toBeUndefined()
  })

  it("reads DMS_CSP_EXTRA_ORIGINS and NEXT_PUBLIC_API_BASE_URL from the build environment", () => {
    const header = reportOnlyPolicyHeader({
      NODE_ENV: "production",
      NEXT_PUBLIC_API_BASE_URL: "http://localhost:8080",
      DMS_CSP_EXTRA_ORIGINS: "https://editor.test *",
    })
    expect(directive(header.value, "connect-src")).toContain("http://localhost:8080")
    expect(directive(header.value, "frame-src")).toContain("https://editor.test")
    expect(header.value).not.toContain("*")
  })
})

describe("next.config headers()", () => {
  it("is wired to the rules", async () => {
    expect(await nextConfig.headers?.()).toEqual(securityHeaderRules())
  })

  it("applies the base headers to every path, including route handlers and error pages", () => {
    const [all] = securityHeaderRules({ NODE_ENV: "production" })
    expect(all.source).toBe("/:path*")
    expect(all.headers.map((h) => h.key)).toEqual([
      "X-Frame-Options",
      "X-Content-Type-Options",
      "Referrer-Policy",
      "Permissions-Policy",
    ])
  })

  it("keeps the report-only policy off _next assets and off the proxied Grafana panels", () => {
    const rule = securityHeaderRules({ NODE_ENV: "production" }).find((r) =>
      r.headers.some((h) => h.key === "Content-Security-Policy-Report-Only"),
    )!
    const pattern = new RegExp(`^${rule.source}$`)
    expect(pattern.test("/login")).toBe(true)
    expect(pattern.test("/documents/abc")).toBe(true)
    expect(pattern.test("/s/some-token")).toBe(true)
    expect(pattern.test("/_next/static/chunks/a.js")).toBe(false)
    expect(pattern.test("/monitoring/panels/d-solo/x")).toBe(false)
    expect(pattern.test("/monitoring")).toBe(true)
  })

  it("gives share links no referrer, after (so over) the base rule", () => {
    const rules = securityHeaderRules({ NODE_ENV: "production" })
    const share = rules.findIndex((r) => r.source === "/s/:path*")
    expect(share).toBeGreaterThan(0)
    expect(rules[share].headers).toEqual([{ key: "Referrer-Policy", value: "no-referrer" }])
  })
})
