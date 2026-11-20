/**
 * Signing in again after the session lapsed (FE-M3-B).
 *
 * `proxy.ts` sends anyone who holds an access cookie from `/login` straight to `/`, because that is right for a person
 * who is signed in and typed the address. But the cookie is only a cookie: dms-core may no longer accept it (rotated
 * signing key, changed issuer, ...) and nothing clears it when there is no refresh cookie to try, so the person landed
 * on `/`, was refused, was sent to `/session-expired`, pressed "Sign in again", and was bounced back to `/` - for as
 * long as the cookie lived.
 *
 * The session-expired page therefore asks for the sign-in form explicitly, and `proxy.ts` lets that one request through.
 * The flag is a request to SEE the form, nothing more: it is not authentication, it grants nothing, and it clears
 * nothing - a person whose session is in fact still valid just sees the form, and their cookies are replaced only if
 * they sign in. It carries no destination, so it cannot redirect anywhere (a `from` value is still validated by
 * `safeReturnPath` on the login page, untouched).
 */
export const SESSION_EXPIRED_FLAG = "expired"

/** Where "Sign in again" goes. */
export const SIGN_IN_AGAIN_PATH = `/login?${SESSION_EXPIRED_FLAG}=1`

/** Whether a request to `/login` is the deliberate "sign in again" one, and so must not be bounced to `/`. */
export function isSignInAgainRequest(searchParams: URLSearchParams): boolean {
  return searchParams.get(SESSION_EXPIRED_FLAG) === "1"
}