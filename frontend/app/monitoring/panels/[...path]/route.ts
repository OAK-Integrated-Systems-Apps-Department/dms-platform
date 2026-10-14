import { type NextRequest } from "next/server"

import { ACCESS_TOKEN_COOKIE } from "@/lib/auth/cookie-names"

/**
 * Serves Grafana's panels under a path of our own, to callers this app has
 * already authenticated.
 *
 * WHY THIS EXISTS
 *
 * The monitoring screen embeds Grafana panels, and an iframe cannot be asked
 * for a second login. The usual workaround is to run Grafana with anonymous
 * access - which is what this installation did, and it meant every metric was
 * readable by anyone who could reach Grafana's port, whatever the DMS thought
 * about them.
 *
 * Instead, this route is the only way in. It asks dms-core whether the
 * caller may see monitoring (platform admin, or a role carrying
 * shad.view-system-health-dashboard), and if so forwards to Grafana with the caller's name
 * in `X-WEBAUTH-USER`. Grafana's auth proxy trusts that header - which is
 * safe exactly because Grafana accepts it from nothing else: it is not
 * published, and GF_AUTH_PROXY_WHITELIST limits who may set it.
 *
 * It also means the browser never sees a URL with "grafana" in it. That is
 * cosmetic, not a security property: anyone who opens devtools can still see
 * Grafana's own assets going past. It keeps the product's surface its own
 * for an ordinary user, nothing more.
 *
 * Everything is proxied, not just the panel HTML - Grafana loads its own
 * scripts, fonts and API calls from under its root URL, which is configured
 * to be this path.
 */

/** Server-side only: this is the internal address, never sent to a browser. */
const GRAFANA_ORIGIN = process.env.GRAFANA_INTERNAL_URL ?? "http://grafana:3000"
const DMS_CORE_ORIGIN =
  process.env.DMS_CORE_INTERNAL_URL ?? "http://dms-core:8080"

/**
 * Stylesheet for a single-panel page.
 *
 * `d-solo` renders one panel, but still draws Grafana's own header on it: a
 * second title (under the card title this page already prints), an info icon,
 * and a menu whose entries - View, Explore, Inspect, Share - open Grafana's
 * own interface. That is the opposite of what the panel proxy is for, and it
 * appeared on hover, over the chart someone is trying to read.
 *
 * The selectors are Grafana's own `data-testid` attributes rather than its
 * class names. The classes are generated per build and change with every
 * release; the test ids are what Grafana's own end-to-end tests depend on, so
 * they move far less. They were checked against this image (11.5.1) in the
 * shipped bundle, and the Grafana tag is pinned in compose, so an upgrade is
 * a deliberate act and the place to look is here.
 *
 * What this costs: the header also carries the small red indicator for a
 * query that failed. With it hidden, a broken panel shows as an empty chart.
 * Whether Prometheus itself is up is on the Services board; a bad query in a
 * panel is a fault in the dashboard-as-code, which is where it is fixed.
 */
const PANEL_CSS = `<style id="dms-panel">
[data-testid="header-container"],
[data-testid="data-testid hover-header-container"],
[data-testid="hover-header-container"],
[data-testid^="data-testid Panel menu"] {
  display: none !important;
}
/* The panel's own frame. It sits inside a bordered card on this page, so a
   second border and background draw a box within a box. */
[data-testid^="data-testid Panel header"] {
  border: 0 !important;
  background: transparent !important;
  box-shadow: none !important;
}
</style>`

/**
 * Hop-by-hop headers belong to one connection and must not be forwarded;
 * `host` has to be dropped too or Grafana builds its redirects from ours.
 */
const SKIP_REQUEST_HEADERS = new Set([
  "host",
  "connection",
  "keep-alive",
  "transfer-encoding",
  "upgrade",
  "proxy-authorization",
  "proxy-authenticate",
  "te",
  "trailer",
  // Never let a caller supply the header Grafana trusts. Without this, the
  // whole scheme is a login form with the password printed on it.
  "x-webauth-user",
  "x-webauth-name",
  "x-webauth-email",
])

const SKIP_RESPONSE_HEADERS = new Set([
  "connection",
  "keep-alive",
  "transfer-encoding",
  "content-encoding",
  "content-length",
  "upgrade",
])

/** Asks dms-core who this is. Null means "not allowed" - the caller is never told why. */
async function authorise(request: NextRequest): Promise<string | null> {
  const token = request.cookies.get(ACCESS_TOKEN_COOKIE)?.value
  if (!token) return null

  try {
    const response = await fetch(
      `${DMS_CORE_ORIGIN}/api/v1/monitoring/panel-auth`,
      {
        headers: { Authorization: `Bearer ${token}` },
        cache: "no-store",
      }
    )
    if (!response.ok) return null
    return response.headers.get("X-Dms-User")
  } catch {
    // dms-core unreachable: fail closed. A monitoring panel is not worth
    // serving on the guess that the caller was probably allowed.
    return null
  }
}

async function handle(request: NextRequest): Promise<Response> {
  const user = await authorise(request)
  if (!user) {
    return new Response("Not authorised to view monitoring panels.", {
      status: 403,
      headers: { "content-type": "text/plain; charset=utf-8" },
    })
  }

  const incoming = new URL(request.url)
  // Passed through UNCHANGED, including the /monitoring/panels prefix this
  // route is mounted at. Grafana is configured with that same prefix as its
  // root URL (GF_SERVER_ROOT_URL + GF_SERVER_SERVE_FROM_SUB_PATH), so it
  // expects to see it and builds its own asset links from it. Stripping it
  // here would make every script tag in the panel point somewhere that does
  // not exist.
  const target = `${GRAFANA_ORIGIN}${incoming.pathname}${incoming.search}`

  const headers = new Headers()
  request.headers.forEach((value, key) => {
    if (!SKIP_REQUEST_HEADERS.has(key.toLowerCase())) headers.set(key, value)
  })
  headers.set("X-WEBAUTH-USER", user)

  let response: Response
  try {
    response = await fetch(target, {
      method: request.method,
      headers,
      body:
        request.method === "GET" || request.method === "HEAD"
          ? undefined
          : await request.arrayBuffer(),
      redirect: "manual",
      // Tie the upstream request to this one. A panel iframe that unmounts
      // - changing the range remounts every frame - abandons whatever it
      // had in flight, and without this those requests run to completion
      // against Grafana and then fail noisily when nothing is listening.
      signal: request.signal,
    })
  } catch (error) {
    // The caller went away. Normal, not a fault: say so quietly rather than
    // letting it surface as an unhandled stream error in the server log.
    if (request.signal.aborted || (error as Error)?.name === "AbortError") {
      return new Response(null, { status: 499 })
    }
    throw error
  }

  const outgoing = new Headers()
  response.headers.forEach((value, key) => {
    if (!SKIP_RESPONSE_HEADERS.has(key.toLowerCase())) outgoing.set(key, value)
  })
  // The page framing these is same-origin now, so the default deny would
  // block our own iframe.
  outgoing.delete("x-frame-options")

  // A single panel page gets Grafana's own header taken off it. Only the
  // d-solo HTML - the scripts, API calls and assets that follow pass through
  // untouched, and so does every other Grafana page.
  const isPanelPage =
    request.method === "GET" &&
    incoming.pathname.includes("/d-solo/") &&
    (response.headers.get("content-type") ?? "").includes("text/html")

  if (isPanelPage) {
    const html = await response.text()
    // Before </head> so the rule is in place before the first paint - added
    // after load, the header would flash up and then disappear.
    const injected = html.includes("</head>")
      ? // A function, not a string: a string replacement treats "$&" and
        // friends specially, which would silently mangle any CSS that
        // ever contains one.
        html.replace("</head>", () => `${PANEL_CSS}</head>`)
      : PANEL_CSS + html
    return new Response(injected, {
      status: response.status,
      statusText: response.statusText,
      headers: outgoing,
    })
  }

  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers: outgoing,
  })
}

export const GET = handle
export const POST = handle
export const PUT = handle
export const PATCH = handle
export const DELETE = handle
export const HEAD = handle

/** Grafana's API calls must not be cached, and neither must a panel's data. */
export const dynamic = "force-dynamic"
