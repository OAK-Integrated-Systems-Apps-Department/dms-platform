// @vitest-environment node
import { describe, expect, it } from "vitest"

import { FORWARDED_REQUEST_HEADERS, TRUSTED_IDENTITY_HEADER, buildUpstreamHeaders } from "./panel-proxy-headers"

const BROWSER_REQUEST = {
  accept: "text/html,application/json",
  "accept-encoding": "gzip, br",
  "accept-language": "en-GB",
  "cache-control": "no-cache",
  "content-type": "application/json",
  "if-none-match": '"abc"',
  origin: "https://dms.example",
  referer: "https://dms.example/monitoring/panels/d-solo/x",
  "user-agent": "Mozilla/5.0",
}

function build(extra: Record<string, string> = {}, user = "monitor.user") {
  return buildUpstreamHeaders(new Headers({ ...BROWSER_REQUEST, ...extra }), user)
}

describe("panel proxy upstream headers - what is dropped", () => {
  it("drops the DMS access and refresh cookies, however they are arranged in the Cookie header", () => {
    for (const cookie of [
      "dms_access_token=a.b.c",
      "dms_refresh_token=r.r.r",
      "dms_access_token=a.b.c; dms_refresh_token=r.r.r",
      "theme=dark; dms_access_token=a.b.c; other=1",
      "grafana_session=abc; dms_refresh_token=r.r.r",
    ]) {
      expect(build({ cookie }).has("cookie")).toBe(false)
    }
  })

  it("drops every cookie, not just the DMS ones - Grafana sets none in auth-proxy mode and needs none", () => {
    expect(build({ cookie: "grafana_session=abc; grafana_session_expiry=123; analytics=xyz" }).has("cookie")).toBe(false)
  })

  it("drops the incoming Authorization header of every scheme", () => {
    for (const authorization of ["Basic YWRtaW46YWRtaW4=", "Bearer eyJhbGciOi.eyJzdWIi.sig", "Token glsa_abc", "basic x"]) {
      expect(build({ authorization }).has("authorization")).toBe(false)
    }
    expect(build({ "proxy-authorization": "Basic x" }).has("proxy-authorization")).toBe(false)
  })

  it("drops X-Forwarded-* and X-Real-IP, and Forwarded", () => {
    const headers = build({
      "x-forwarded-for": "6.6.6.6",
      "x-forwarded-host": "evil.example",
      "x-forwarded-proto": "https",
      "x-forwarded-port": "443",
      "x-forwarded-prefix": "/x",
      "x-real-ip": "6.6.6.6",
      forwarded: "for=6.6.6.6",
    })
    for (const name of ["x-forwarded-for", "x-forwarded-host", "x-forwarded-proto", "x-forwarded-port", "x-forwarded-prefix", "x-real-ip", "forwarded"]) {
      expect(headers.has(name)).toBe(false)
    }
  })

  it("drops any other identity or proxy-authentication header the caller invents", () => {
    const headers = build({
      "x-webauth-name": "Admin",
      "x-webauth-email": "admin@example.test",
      "x-webauth-role": "Admin",
      "x-auth-request-user": "admin",
      "x-remote-user": "admin",
      "remote-user": "admin",
      "x-dms-user": "admin",
    })
    for (const name of ["x-webauth-name", "x-webauth-email", "x-webauth-role", "x-auth-request-user", "x-remote-user", "remote-user", "x-dms-user"]) {
      expect(headers.has(name)).toBe(false)
    }
  })

  it("does not forward an unknown header by default - a header nobody has listed never leaves", () => {
    const headers = build({
      "x-custom-secret": "top-secret",
      "x-api-key": "k",
      "x-future-header": "whatever",
      "x-grafana-org-id": "1",
      "x-datasource-uid": "u",
      "x-plugin-id": "p",
      "x-panel-id": "1",
      "x-dashboard-uid": "d",
      "sec-fetch-site": "same-origin",
      "x-csrf-token": "t",
    })
    for (const name of [
      "x-custom-secret", "x-api-key", "x-future-header", "x-grafana-org-id", "x-datasource-uid", "x-plugin-id",
      "x-panel-id", "x-dashboard-uid", "sec-fetch-site", "x-csrf-token",
    ]) {
      expect(headers.has(name)).toBe(false)
    }
  })

  it("matches header names case-insensitively (a re-cased Cookie or Authorization is still dropped)", () => {
    const incoming = new Headers()
    incoming.append("COOKIE", "dms_access_token=a")
    incoming.append("AuthoriZation", "Bearer t")
    incoming.append("X-Forwarded-For", "6.6.6.6")
    incoming.append("X-WebAuth-User", "attacker")
    const upstream = buildUpstreamHeaders(incoming, "monitor.user")
    expect(upstream.has("cookie")).toBe(false)
    expect(upstream.has("authorization")).toBe(false)
    expect(upstream.has("x-forwarded-for")).toBe(false)
    expect(upstream.get("x-webauth-user")).toBe("monitor.user")
  })
})

describe("panel proxy upstream headers - the trusted identity", () => {
  it("sets X-WEBAUTH-USER from the user dms-core named", () => {
    expect(build().get(TRUSTED_IDENTITY_HEADER)).toBe("monitor.user")
  })

  it("replaces whatever the caller sent as X-WEBAUTH-USER, in any case, and sends it once", () => {
    for (const name of ["x-webauth-user", "X-WEBAUTH-USER", "X-WebAuth-User"]) {
      const headers = build({ [name]: "attacker" })
      expect(headers.get("x-webauth-user")).toBe("monitor.user")
      expect([...headers.keys()].filter((key) => key === "x-webauth-user")).toHaveLength(1)
    }
  })

  it("refuses to build without a user from dms-core", () => {
    expect(() => buildUpstreamHeaders(new Headers(BROWSER_REQUEST), "")).toThrow()
  })
})

describe("panel proxy upstream headers - what is preserved", () => {
  it("keeps the ordinary headers a page, its assets and its data queries need", () => {
    const headers = build()
    for (const [name, value] of Object.entries(BROWSER_REQUEST)) {
      expect(headers.get(name)).toBe(value)
    }
  })

  it("keeps conditional and range headers", () => {
    const headers = build({ "if-modified-since": "Wed, 21 Oct 2026 07:28:00 GMT", range: "bytes=0-99", "if-range": '"abc"', pragma: "no-cache" })
    expect(headers.get("if-modified-since")).toBe("Wed, 21 Oct 2026 07:28:00 GMT")
    expect(headers.get("range")).toBe("bytes=0-99")
    expect(headers.get("if-range")).toBe('"abc"')
    expect(headers.get("pragma")).toBe("no-cache")
  })

  it("sends nothing but the allow-list and the identity header", () => {
    const headers = build({
      cookie: "dms_access_token=a",
      authorization: "Bearer t",
      "x-forwarded-for": "6.6.6.6",
      "x-custom": "c",
      host: "internal",
      connection: "keep-alive",
    })
    const allowed = new Set([...FORWARDED_REQUEST_HEADERS, "x-webauth-user"])
    expect([...headers.keys()].filter((name) => !allowed.has(name))).toEqual([])
  })

  it("does not read or alter the incoming headers", () => {
    const incoming = new Headers({ ...BROWSER_REQUEST, cookie: "dms_access_token=a" })
    buildUpstreamHeaders(incoming, "monitor.user")
    expect(incoming.get("cookie")).toBe("dms_access_token=a")
    expect(incoming.has("x-webauth-user")).toBe(false)
  })
})
