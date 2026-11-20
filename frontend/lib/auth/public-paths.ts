/**
 * Which paths a signed-out visitor may open (FE-L1). `proxy.ts` redirects everyone else to `/login`.
 *
 * It used to be a list of prefixes tested with `startsWith`, so `/loginfoo`, `/error-foo` or `/portal-admin` counted as
 * public too. Nothing was exposed (those are not pages, and every call the app makes is checked by dms-core), but the
 * visitor got a not-found page instead of the sign-in redirect, and any future route that happened to begin with one of
 * those names would have been silently exempt from the proxy.
 *
 * Now a path is public only if it IS one of the public pages, or lies BELOW one of the two public sections:
 * - `/portal` (guest sign-in and the portal, `/portal/accept`, ...) and everything under it: for people with no account;
 * - `/s/<token>`: an external share link. A token is required - bare `/s` and `/s/` are not pages, and stay protected.
 *
 * Matching is on whole path segments, on the pathname exactly as the proxy receives it. It is deliberately not decoded
 * or normalised here: Next has already collapsed repeated slashes, redirected a trailing slash and resolved dot
 * segments before the proxy runs (checked against a production build), and a percent-encoded look-alike such as
 * `/%6Cogin` simply does not match and is treated as protected - the safe direction.
 */

/** Standalone public pages: matched exactly, nothing may follow. */
const PUBLIC_PAGES: ReadonlySet<string> = new Set([
  "/login",
  "/login-timeout",
  "/reset-password",
  "/update-password",
  "/error",
  "/session-expired",
])

/** Public sections: the section's own page and everything below it. */
const PUBLIC_SECTIONS = ["/portal"]

/** Public only BELOW the prefix, never the prefix itself: `/s/<token>`. */
const PUBLIC_BELOW = ["/s/"]

export function isPublicPath(pathname: string): boolean {
  if (PUBLIC_PAGES.has(pathname)) return true
  if (PUBLIC_SECTIONS.some((section) => pathname === section || pathname.startsWith(`${section}/`))) return true
  return PUBLIC_BELOW.some((prefix) => pathname.startsWith(prefix) && pathname.length > prefix.length)
}