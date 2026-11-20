// @vitest-environment node
import { NextRequest } from "next/server"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import * as route from "./route"

const CORE = "http://dms-core:8080/api/v1/monitoring/panel-auth"
const GRAFANA = "http://grafana:3000"

type Call = { url: string; init: RequestInit }
let calls: Call[]
let panelAuth: () => Response | Promise<Response>

function stubFetch() {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init: RequestInit = {}) => {
      calls.push({ url: String(url), init })
      if (String(url) === CORE) return panelAuth()
      return new Response("grafana body", { status: 200, headers: { "content-type": "text/plain" } })
    }),
  )
}

const SENSITIVE = {
  cookie: "dms_access_token=synthetic-access; dms_refresh_token=synthetic-refresh; theme=dark",
  authorization: "Basic c3ludGhldGljOnBhc3M=",
  "x-forwarded-for": "6.6.6.6",
  "x-real-ip": "6.6.6.6",
  "x-webauth-user": "attacker",
  "x-custom-secret": "top-secret",
}

function request(method: string, init: { headers?: Record<string, string>; body?: string } = {}) {
  return new NextRequest("http://dms.test/monitoring/panels/api/ds/query?x=1", {
    method,
    headers: { accept: "application/json", "content-type": "application/json", origin: "http://dms.test", ...SENSITIVE, ...init.headers },
    body: init.body,
  })
}

const grafanaCalls = () => calls.filter((c) => c.url.startsWith(GRAFANA))
const grafanaHeaders = () => new Headers(grafanaCalls()[0].init.headers)

beforeEach(() => {
  calls = []
  panelAuth = () => new Response(null, { status: 204, headers: { "X-Dms-User": "monitor.user" } })
  stubFetch()
})
afterEach(() => vi.unstubAllGlobals())

describe("monitoring panel proxy - authorisation is unchanged", () => {
  it("answers 403 and never reaches Grafana without the access cookie", async () => {
    const response = await route.GET(new NextRequest("http://dms.test/monitoring/panels/x", { headers: { accept: "text/html" } }))

    expect(response.status).toBe(403)
    expect(calls).toHaveLength(0)
  })

  it("answers 403 and never reaches Grafana when dms-core refuses the caller", async () => {
    panelAuth = () => new Response(null, { status: 403 })

    const response = await route.GET(request("GET"))

    expect(response.status).toBe(403)
    expect(calls.map((c) => c.url)).toEqual([CORE])
    expect(grafanaCalls()).toHaveLength(0)
  })

  it("fails closed when dms-core cannot be reached", async () => {
    panelAuth = () => {
      throw new Error("connection refused")
    }

    const response = await route.GET(request("GET"))

    expect(response.status).toBe(403)
    expect(grafanaCalls()).toHaveLength(0)
  })

  it("fails closed when dms-core approves but names nobody", async () => {
    panelAuth = () => new Response(null, { status: 204 })

    const response = await route.GET(request("GET"))

    expect(response.status).toBe(403)
    expect(grafanaCalls()).toHaveLength(0)
  })

  it("uses the access token only for the dms-core authorisation call", async () => {
    await route.GET(request("GET", { headers: { cookie: "dms_access_token=synthetic-access" } }))

    const core = calls.find((c) => c.url === CORE)!
    expect(new Headers(core.init.headers).get("authorization")).toBe("Bearer synthetic-access")
    const sentToGrafana = JSON.stringify([...grafanaHeaders().entries()])
    expect(sentToGrafana).not.toContain("synthetic-access")
  })
})

describe("monitoring panel proxy - what Grafana receives", () => {
  it.each(["GET", "HEAD", "POST", "PUT", "PATCH", "DELETE"] as const)(
    "%s: no DMS cookie, no Authorization, no X-Forwarded-*, no unknown header; identity from dms-core",
    async (method) => {
      const handler = route[method]
      const body = method === "GET" || method === "HEAD" ? undefined : '{"queries":[]}'

      const response = await handler(request(method, { body }))

      expect(response.status).toBe(200)
      expect(grafanaCalls()).toHaveLength(1)
      const headers = grafanaHeaders()
      for (const name of ["cookie", "authorization", "x-forwarded-for", "x-real-ip", "x-custom-secret"]) {
        expect(headers.has(name)).toBe(false)
      }
      expect(headers.get("x-webauth-user")).toBe("monitor.user")
      expect(headers.get("accept")).toBe("application/json")
      expect(headers.get("origin")).toBe("http://dms.test")
      expect(JSON.stringify([...headers.entries()])).not.toMatch(/synthetic|attacker|top-secret|6\.6\.6\.6/)
    },
  )

  it("sends the request to Grafana's internal address with the same method, path and query", async () => {
    await route.POST(request("POST", { body: '{"queries":[]}' }))

    expect(grafanaCalls()[0].url).toBe(`${GRAFANA}/monitoring/panels/api/ds/query?x=1`)
    expect(grafanaCalls()[0].init.method).toBe("POST")
  })

  it("preserves the request body of a data query byte for byte", async () => {
    const body = '{"queries":[{"refId":"A","datasource":{"uid":"prom"}}],"from":"now-5m"}'

    await route.POST(request("POST", { body }))

    const sent = grafanaCalls()[0].init.body as ArrayBuffer
    expect(new TextDecoder().decode(sent)).toBe(body)
  })

  it("sends no body for GET and HEAD", async () => {
    await route.GET(request("GET"))
    await route.HEAD(request("HEAD"))

    for (const call of grafanaCalls()) expect(call.init.body).toBeUndefined()
  })

  it("does not trust a client-supplied identity, whatever its case", async () => {
    await route.GET(request("GET", { headers: { "X-WEBAUTH-USER": "attacker", "x-webauth-email": "admin@x" } }))

    const headers = grafanaHeaders()
    expect(headers.get("x-webauth-user")).toBe("monitor.user")
    expect(headers.has("x-webauth-email")).toBe(false)
  })

  it("does not forward a header it has never heard of", async () => {
    await route.GET(request("GET", { headers: { "x-brand-new-header": "value", "x-another-token": "t" } }))

    const headers = grafanaHeaders()
    expect(headers.has("x-brand-new-header")).toBe(false)
    expect(headers.has("x-another-token")).toBe(false)
  })
})

describe("monitoring panel proxy - what the browser receives", () => {
  const withCookies = (cookies: string[]) => {
    const headers = new Headers({ "content-type": "text/plain", "x-grafana-keep": "yes" })
    for (const cookie of cookies) headers.append("set-cookie", cookie)
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string, init: RequestInit = {}) => {
        calls.push({ url: String(url), init })
        if (String(url) === CORE) return panelAuth()
        return new Response("grafana body", { status: 200, headers })
      }),
    )
  }

  it("never passes a Set-Cookie through - Grafana cannot set or replace a DMS sign-in cookie", async () => {
    withCookies([
      "dms_access_token=forged; Path=/; HttpOnly",
      "dms_refresh_token=forged; Path=/; HttpOnly",
      "grafana_session=abc; Path=/monitoring/panels/",
      "other=1; Path=/",
    ])

    const response = await route.GET(request("GET"))

    expect(response.status).toBe(200)
    expect(response.headers.has("set-cookie")).toBe(false)
    expect(response.headers.getSetCookie()).toEqual([])
  })

  it("still passes Grafana's other response headers and body through", async () => {
    withCookies(["grafana_session=abc"])

    const response = await route.GET(request("GET"))

    expect(response.headers.get("x-grafana-keep")).toBe("yes")
    expect(response.headers.get("content-type")).toBe("text/plain")
    expect(await response.text()).toBe("grafana body")
  })
})
