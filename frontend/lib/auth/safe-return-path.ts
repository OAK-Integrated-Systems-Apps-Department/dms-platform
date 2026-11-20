/**
 * Where to send someone after they sign in (FE-M1 / FUN-02).
 *
 * `proxy.ts` sends an unauthenticated visitor of a protected page to `/login?from=<path>`; the login page uses the
 * value of `from` once sign-in has succeeded. That value is typed into a URL by whoever built the link, so it must be
 * treated as hostile: `/login?from=//evil.example` must never send a freshly signed-in person to another site, and
 * `/login?from=/login` must never loop.
 *
 * The rule is "an ordinary path of this application, or `/`". Anything doubtful returns `/`; this function never throws
 * and never returns a value that is not a path starting with a single `/`.
 */

/** Pages a person must not be sent back to after signing in - they are the sign-in flow itself (exact path or below). */
const AUTH_PAGES = ["/login", "/session-expired", "/login-timeout", "/reset-password", "/update-password", "/error"]

/** A generous ceiling for a path with a query string; anything longer is not a link a person followed. */
const MAX_LENGTH = 2048

const FALLBACK = "/"

/** Parsing happens against a made-up origin: the result must still be on it, so nothing can name another host. */
const PARSE_BASE = "http://return-path.invalid"

/** Control characters (tab, newline and NUL included - URL parsers silently drop some of them) and backslashes. */
const UNSAFE_CHARACTERS = /[\u0000-\u001f\u007f\\]/

/** A percent-escape. After one decode there must be none left: that is a double-encoded value. */
const PERCENT_ESCAPE = /%[0-9a-fA-F]{2}/

function isAuthPage(pathname: string): boolean {
  const lower = pathname.toLowerCase()
  return AUTH_PAGES.some((page) => lower === page || lower.startsWith(`${page}/`))
}

export function safeReturnPath(value: string | null | undefined): string {
  if (typeof value !== "string" || value.length === 0 || value.length > MAX_LENGTH) return FALLBACK
  if (value.trim() !== value) return FALLBACK // blank, or padded with spaces: not a link anyone built on purpose

  // As written: one single leading slash, no backslash, no control character.
  if (!value.startsWith("/") || value.startsWith("//") || UNSAFE_CHARACTERS.test(value)) return FALLBACK

  // Decoded ONCE, to see what the browser and the server will see. Malformed escapes, an encoded backslash or control
  // character, an encoded "//" at the start, and a second layer of encoding are all refused - no repeated decoding.
  let decoded: string
  try {
    decoded = decodeURIComponent(value)
  } catch {
    return FALLBACK
  }
  if (UNSAFE_CHARACTERS.test(decoded) || decoded.startsWith("//") || PERCENT_ESCAPE.test(decoded)) return FALLBACK

  // The URL parser is the authority on what the value means: it must stay on our made-up origin, and its normalised
  // path (dot segments resolved) is what is checked against the sign-in pages and what is returned.
  let url: URL
  try {
    url = new URL(value, PARSE_BASE)
  } catch {
    return FALLBACK
  }
  if (url.origin !== PARSE_BASE || url.username || url.password) return FALLBACK
  if (url.pathname.startsWith("//")) return FALLBACK

  let pathname: string
  try {
    pathname = decodeURIComponent(url.pathname) // "/%6Cogin" is "/login" to the server
  } catch {
    return FALLBACK
  }
  if (UNSAFE_CHARACTERS.test(pathname) || isAuthPage(url.pathname) || isAuthPage(pathname)) return FALLBACK

  return `${url.pathname}${url.search}${url.hash}`
}
