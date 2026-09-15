/**
 * Thrown by `apiFetch`/`apiFetchJson` (lib/api-fetch.ts) for every non-2xx
 * response and every network failure, in place of the bare `throw new
 * Error(...)` every authenticated fetch call used to do individually.
 * Carrying `status` is the whole point: a plain `Error` loses the HTTP
 * status the moment it's thrown, which made it impossible for a single,
 * central place (the global `QueryCache`/`MutationCache` `onError` in
 * `components/providers/providers.tsx`) to ever tell "this failed because
 * the session expired (401)" apart from any other failure.
 *
 * `status` is `0` for a network failure (the request never reached the
 * server at all) - distinguishable from a real HTTP status, for callers
 * that want to keep the old "never reached the server" vs "server
 * responded with an error" distinction some stores already made.
 */
export class ApiError extends Error {
  readonly status: number
  /** The backend's optional machine-readable `code` (see ErrorResponse/ApiException on the backend), e.g. "TENANT_CONTEXT_MISSING" - undefined when the response body didn't carry one. */
  readonly code?: string

  constructor(status: number, message: string, code?: string) {
    super(message)
    this.name = "ApiError"
    this.status = status
    this.code = code
  }
}

/**
 * Whether `error` represents an expired/invalid session - a 401 from any
 * call that assumed it already had one. This is deliberately narrow (only
 * `ApiError` with `status === 401`) so it never misfires on an unrelated
 * failure (404, 500, network error, validation error, etc.), which the
 * caller's own local error handling still deals with unchanged.
 * <p>
 * Two backend exceptions both map to 401 -
 * `InvalidCredentialsException` (wrong username/password/OTP on
 * `POST /api/v1/auth/login`) and `SessionExpiredException` (expired
 * refresh token). Only the second one should ever reach here: the login
 * flow (`app/login/login-page-content.tsx`'s `login`/`checkMfaRequired`)
 * deliberately catches its own errors and never lets a rejected fetch
 * propagate to React Query, precisely so a wrong-password attempt can
 * never be mistaken for an expired session by the global handler below.
 * Do not "simplify" that flow by removing its try/catch and letting
 * `apiFetchJson` throw naturally - that would make every wrong-password
 * login attempt show "Your session has expired" and redirect away from
 * the login page instead of showing an inline error.
 */
export function isSessionExpired(error: unknown): boolean {
  return (
    error instanceof ApiError &&
    error.status === 401 &&
    // A 401 for an authenticated caller who simply has no tenant context yet
    // (see TenantContextFilter/GlobalExceptionHandler on the backend - a
    // platform-admin-only login, or a brand-new signup whose self-provisioning
    // hasn't run yet) is not an expired session and must not be treated as
    // one: that would incorrectly clear the whole query cache and bounce the
    // caller to /session-expired instead of letting the caller's own
    // isError/error UI show the real "provisioning incomplete" message.
    error.code !== "TENANT_CONTEXT_MISSING"
  )
}

/**
 * Whether `error` represents a connection-level failure — the request
 * never reached the server at all (backend down, network error, connection
 * refused), as opposed to the server responding with a real HTTP status.
 * `apiFetch` (lib/api-fetch.ts) is the only place that produces this —
 * every `fetch()` throw there becomes `ApiError(0, NETWORK_ERROR_MESSAGE)`.
 * Distinct from `isSessionExpired`: this is the other narrow, single-purpose
 * check the global `QueryCache`/`MutationCache` `onError` in
 * `components/providers/providers.tsx` uses, so a dropped connection isn't
 * mistaken for an expired session (or vice versa).
 */
export function isNetworkError(error: unknown): boolean {
  return error instanceof ApiError && error.status === 0
}

/**
 * A friendly message for `error`, special-casing 403 the same way
 * `stores/roles-store.ts` and `stores/permissions-store.ts` each used to
 * (as two separate, near-identical `parseErrorMessage` helpers) - kept
 * here instead so both stores share one definition. Anything else falls
 * back to `error.message` (already the right text for a 4xx/5xx response
 * or a network failure - see `apiFetch`), or `fallback` if `error` isn't
 * even an `Error`.
 */
export function friendlyMessage(
  error: unknown,
  fallback = "Something went wrong. Please try again."
): string {
  if (error instanceof ApiError && error.status === 403) {
    return "You don't have permission to do that."
  }
  return error instanceof Error ? error.message : fallback
}
