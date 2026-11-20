/**
 * The request headers the monitoring panel proxy (`app/monitoring/panels/[...path]/route.ts`) sends on to Grafana
 * (FE-M2).
 *
 * The proxy runs in the DMS origin, so the browser attaches everything it holds for that origin to every request -
 * including the DMS sign-in cookies (`dms_access_token`, `dms_refresh_token`, both `Path=/`) - and the front proxy adds
 * its own `X-Forwarded-*`. None of that is Grafana's business, and one part of it is dangerous: Grafana tries an
 * `Authorization` header (Basic or API token) BEFORE the trusted `X-WEBAUTH-USER` header, so a caller who forwards
 * their own would be whoever those credentials say, not the person dms-core vouched for (shown against a disposable
 * Grafana: the same caller was `monitor.user` without the header and the Grafana admin with it).
 *
 * So this is an ALLOW-list, not a block-list: only the ordinary HTTP headers a page, its assets and its data queries
 * need are copied; a header nobody has thought about - a new cookie, a token, a custom header - is dropped by default.
 * Verified against Grafana 11.5.1 with the auth proxy enabled: the dashboard page, its scripts, its API calls and its
 * `POST /api/ds/query` all work with exactly this set, and Grafana sets no session cookie in that mode, so no cookie is
 * forwarded at all. The Grafana frontend also sends `X-Grafana-Org-Id`, `X-Datasource-Uid`, `X-Plugin-Id`,
 * `X-Panel-Id`, `X-Dashboard-Uid` and similar; they are informational (there is one organisation, and the query body
 * names the data source) and are deliberately not forwarded - add one here only with a demonstrated need.
 *
 * `X-WEBAUTH-USER` is set from dms-core's answer and from nothing else; whatever the caller sent under that name (or
 * any other name) never reaches Grafana.
 */

/** Lower-case names. Everything not listed here is dropped. */
export const FORWARDED_REQUEST_HEADERS: ReadonlySet<string> = new Set([
  "accept",
  "accept-encoding", // the response is decoded here and re-sent without content-encoding, as before
  "accept-language",
  "cache-control",
  "content-type", // the body of a data query
  "if-modified-since",
  "if-none-match",
  "if-range",
  "origin", // Grafana's CSRF check on POST compares it with the root URL
  "pragma",
  "range",
  "referer",
  "user-agent",
])

/** The header Grafana's auth proxy trusts to name the caller. */
export const TRUSTED_IDENTITY_HEADER = "X-WEBAUTH-USER"

/**
 * @param incoming the browser's request headers, as this app received them
 * @param user the caller's name as dms-core's panel-auth answered it - the only source of the identity header
 */
export function buildUpstreamHeaders(incoming: Headers, user: string): Headers {
  if (!user) {
    throw new Error("A panel request needs the caller's name from dms-core")
  }
  const upstream = new Headers()
  incoming.forEach((value, name) => {
    if (FORWARDED_REQUEST_HEADERS.has(name.toLowerCase())) upstream.set(name, value)
  })
  upstream.set(TRUSTED_IDENTITY_HEADER, user)
  return upstream
}
