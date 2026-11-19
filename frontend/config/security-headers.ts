/**
 * HTTP security headers for the pages and route handlers this Next.js app serves (FE-H1).
 *
 * Scope: only what Next.js sends - the app's HTML, its static files and its own route handlers. The API
 * (`/api/`) is answered by dms-core straight through nginx and keeps its own headers (X-Frame-Options, nosniff, the
 * content-serving CSP sandbox - DOC-04), and Keycloak's pages (`/realms/`, `/resources/`) keep Keycloak's. Nothing here
 * is added to or removed from those.
 *
 * Enforced now:
 *  - X-Frame-Options: SAMEORIGIN - not DENY: the monitoring page frames its own `/monitoring/panels/` route.
 *  - X-Content-Type-Options: nosniff
 *  - Referrer-Policy: strict-origin-when-cross-origin; `no-referrer` on share links (`/s/<token>`), whose address is
 *    itself the secret.
 *  - Permissions-Policy: the capabilities the app never uses are switched off. Clipboard and fullscreen are NOT listed:
 *    the Copy buttons use `navigator.clipboard.writeText` and the online editor's iframe asks for
 *    `clipboard-read; clipboard-write; fullscreen`, so their defaults stay.
 *
 * Report-only for now: Content-Security-Policy-Report-Only. It never blocks anything; browsers log would-be violations
 * to the console (there is no report endpoint). `frame-ancestors` is deliberately absent - it is ignored in a
 * report-only policy, so framing protection comes from X-Frame-Options until the policy is enforced.
 *
 * Not set, on purpose: Strict-Transport-Security (the documented deployment is plain HTTP on the LAN; a browser ignores
 * HSTS over HTTP and it must only be added once nginx serves HTTPS - see docs/deployment.md) and the Cross-Origin-*
 * isolation headers (COEP would break the cross-origin editor frame).
 */

export type HeaderEntry = { key: string; value: string }

export type PolicyOptions = {
  /** `next dev` needs eval for React's debugging tools; a production build must not. */
  development: boolean
  /** The browser-side API origin, only when the build points at one (empty = same origin, as in production). */
  apiBaseUrl?: string
  /** Extra origins the page may frame and post forms to, e.g. the online editor's address. */
  extraOrigins?: string[]
}

/**
 * Turns "https://editor.example, http://10.0.0.5:9980" (space or comma separated) into a list of origins. Anything
 * that is not a plain http(s) origin is dropped, so a typo cannot widen the policy: no wildcards, no paths, no schemes
 * other than http and https.
 */
export function parseOrigins(raw: string | undefined): string[] {
  if (!raw) return []
  const origins = new Set<string>()
  for (const token of raw.split(/[\s,]+/).filter(Boolean)) {
    try {
      const url = new URL(token)
      if ((url.protocol === "http:" || url.protocol === "https:") && !url.username && !url.password && url.origin !== "null") {
        origins.add(url.origin)
      }
    } catch {
      // not a URL: ignored
    }
  }
  return [...origins]
}

/** The origin of an absolute API base URL, or none for the empty/same-origin build. */
export function originOf(url: string | undefined): string | undefined {
  return parseOrigins(url)[0]
}

/**
 * The report-only Content-Security-Policy. Every directive is justified by something the app does:
 *  - script-src: Next.js writes inline bootstrap scripts into every page and this policy has no nonce yet, hence
 *    'unsafe-inline' for now; 'unsafe-eval' only in development.
 *  - style-src: components set inline `style` attributes, and docx-preview and the editor write style elements.
 *  - img-src: previews are blob: URLs; data: for inlined icons.
 *  - media-src: the preview plays audio and video from blob: URLs.
 *  - connect-src: the app calls its own origin (`/api/v1/...`); in development the API is another origin.
 *  - frame-src: previews are shown in iframes pointing at blob: URLs, the monitoring panels are same-origin, the email
 *    preview is a `srcdoc` frame; the online editor (Collabora) is another origin, added only when configured.
 *  - form-action: the online editor is opened by posting a form to it.
 *  - font-src 'self': next/font self-hosts its fonts at build time - no font CDN.
 *  - object-src 'none', base-uri 'self': nothing needs them.
 */
export function contentSecurityPolicy(options: PolicyOptions): string {
  const api = originOf(options.apiBaseUrl)
  const extra = (options.extraOrigins ?? []).filter(Boolean)
  const directives: Array<[string, string[]]> = [
    ["default-src", ["'self'"]],
    ["script-src", ["'self'", "'unsafe-inline'", ...(options.development ? ["'unsafe-eval'"] : [])]],
    ["style-src", ["'self'", "'unsafe-inline'"]],
    ["img-src", ["'self'", "blob:", "data:"]],
    ["font-src", ["'self'"]],
    ["connect-src", ["'self'", ...(api ? [api] : [])]],
    ["media-src", ["'self'", "blob:"]],
    ["frame-src", ["'self'", "blob:", ...extra]],
    ["form-action", ["'self'", ...extra]],
    ["base-uri", ["'self'"]],
    ["object-src", ["'none'"]],
  ]
  return directives.map(([name, values]) => `${name} ${values.join(" ")}`).join("; ")
}

/** Capabilities the app does not use. Clipboard and fullscreen are intentionally absent (see the file header). */
export const PERMISSIONS_POLICY = [
  "camera=()",
  "microphone=()",
  "geolocation=()",
  "payment=()",
  "usb=()",
  "display-capture=()",
  "accelerometer=()",
  "gyroscope=()",
  "magnetometer=()",
  "midi=()",
].join(", ")

/** Headers for every response Next.js produces. */
export function baseSecurityHeaders(): HeaderEntry[] {
  return [
    { key: "X-Frame-Options", value: "SAMEORIGIN" },
    { key: "X-Content-Type-Options", value: "nosniff" },
    { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
    { key: "Permissions-Policy", value: PERMISSIONS_POLICY },
  ]
}

/** The report-only policy, from the build's environment. */
export function reportOnlyPolicyHeader(env: Record<string, string | undefined> = process.env): HeaderEntry {
  return {
    key: "Content-Security-Policy-Report-Only",
    value: contentSecurityPolicy({
      development: env.NODE_ENV === "development",
      apiBaseUrl: env.NEXT_PUBLIC_API_BASE_URL,
      extraOrigins: parseOrigins(env.DMS_CSP_EXTRA_ORIGINS),
    }),
  }
}

/**
 * The Next.js `headers()` rules. Later rules override earlier ones for the same header.
 *  1. Everything: the base headers.
 *  2. The report-only CSP on pages - not on `_next/` assets (no document there) and not on `/monitoring/panels/`,
 *     which forwards Grafana's own pages: they carry inline scripts a policy written for this app would flag, and
 *     would break the day the policy is enforced. Grafana is framed same-origin (X-Frame-Options still applies).
 *  3. Share links: the address is the secret, so no referrer at all.
 */
export function securityHeaderRules(env: Record<string, string | undefined> = process.env) {
  return [
    { source: "/:path*", headers: baseSecurityHeaders() },
    { source: "/((?!_next/|monitoring/panels/).*)", headers: [reportOnlyPolicyHeader(env)] },
    { source: "/s/:path*", headers: [{ key: "Referrer-Policy", value: "no-referrer" }] },
  ]
}
