import { ApiError } from "@/lib/api-error"
import { API_BASE_URL } from "@/lib/api-config"

/**
 * Backend-issued, signed continuation token (see
 * AuditRequestContextFilter/AuditCorrelationTokenCodec) that groups a
 * related SEQUENCE of requests under one audit correlation_id - e.g.
 * roles-store.ts's addRole (create role, then assign its initial
 * permissions). Never a value this app makes up itself: always read from a
 * prior response via `correlationTokenFrom` and threaded into the next
 * related call via `withCorrelation`. Omitting it (the default for every
 * unrelated call) just means that request starts its own correlation,
 * which is always correct - this is purely an opt-in grouping hint, never
 * required for a request to succeed.
 */
const CORRELATION_HEADER = "X-Correlation-Id"

/** Reads the correlation continuation token off a response, if the backend issued one (it does for every authenticated request - see AuditRequestContextFilter). */
export function correlationTokenFrom(response: Response): string | null {
  return response.headers.get(CORRELATION_HEADER)
}

/** Merges a correlation continuation token into a fetch `init`, if one is supplied - returns `init` unchanged when `token` is null/undefined. */
export function withCorrelation(init: RequestInit, token?: string | null): RequestInit {
  if (!token) return init
  return {
    ...init,
    headers: { ...(init.headers as Record<string, string> | undefined), [CORRELATION_HEADER]: token },
  }
}

/** Exported so callers (e.g. the shared "backend unavailable" UI) can reuse the exact copy instead of re-typing it, or compare against a caught error's `.message`. */
export const NETWORK_ERROR_MESSAGE =
  "Could not reach the server. Check that the backend is running and try again."

const REFRESH_URL = `${API_BASE_URL}/api/v1/auth/refresh`

/**
 * dms-core's own auth endpoints (see SecurityConfig's PUBLIC_ENDPOINTS) are
 * excluded from the refresh-and-retry below - a 401 from one of these means
 * something other than "my access token expired mid-session" (wrong
 * credentials on /login, an already-invalid refresh token on /refresh
 * itself), and refreshing/retrying wouldn't fix any of that. This is also
 * what keeps a wrong-password /login attempt's 401 reaching
 * login-page-content.tsx's own try/catch unchanged - see that file's
 * `login()` doc comment for why that path must stay untouched.
 */
function isAuthEndpoint(input: string): boolean {
  return input.includes("/api/v1/auth/")
}

/**
 * dms-core's AuditRequestContextFilter reads this to populate an audit
 * event's `client_type` column - defaulting to WEB itself when the header
 * is absent, which every call from this app always was. Set once, here,
 * rather than at each of the ~30 call sites - the same reasoning as
 * `credentials: "include"` below.
 */
const CLIENT_TYPE_HEADER = "X-Client-Type"
const CLIENT_TYPE = "WEB"

/**
 * Calls `fetch` with the two things every dms-core call needs
 * (`credentials: "include"`, to send the session cookie, and the client
 * type header above), translating a thrown network failure into the same
 * `ApiError(0, ...)` shape `apiFetch` has always thrown - kept separate
 * from `apiFetch` itself so the retry below can call it a second time
 * without re-running the 401 handling.
 */
async function fetchWithCredentials(input: string, init: RequestInit): Promise<Response> {
  try {
    return await fetch(input, {
      ...init,
      credentials: "include",
      headers: { ...(init.headers as Record<string, string> | undefined), [CLIENT_TYPE_HEADER]: CLIENT_TYPE },
    })
  } catch {
    throw new ApiError(0, NETWORK_ERROR_MESSAGE)
  }
}

/**
 * Renews the session from the refresh-token cookie (`POST
 * /api/v1/auth/refresh`) - the browser applies the response's rotated
 * `Set-Cookie` headers automatically since both cookies are httpOnly, so
 * there's nothing for this function to read or store itself. Resolves to
 * whether the refresh succeeded; never throws (a network failure or a
 * non-2xx both just mean "couldn't refresh").
 *
 * Memoized while in flight - `apiFetch` calls this on every 401 it sees, and
 * a lapsed session commonly fails several concurrent requests at once (a
 * dashboard with multiple queries in flight). Without this, each of those
 * would race its own `/auth/refresh` call; with it, they all await the same
 * promise, so exactly one refresh happens per lapse. Reset to `null` once
 * the call settles (success or failure), so the next lapse - later, after
 * signing back in - starts a fresh refresh rather than reusing a stale
 * result.
 *
 * Exported so `useProactiveSessionRefresh` (lib/auth/use-proactive-session-
 * refresh.ts) can call the exact same function on a timer, ahead of any
 * 401 - sharing this one in-flight guard means a proactive refresh and a
 * reactive one from apiFetch can never race each other into two concurrent
 * `/auth/refresh` calls either.
 */
let refreshPromise: Promise<boolean> | null = null

export function refreshSession(): Promise<boolean> {
  if (!refreshPromise) {
    refreshPromise = fetch(REFRESH_URL, {
      method: "POST",
      credentials: "include",
      headers: { [CLIENT_TYPE_HEADER]: CLIENT_TYPE },
    })
      .then((response) => response.ok)
      .catch(() => false)
      .finally(() => {
        refreshPromise = null
      })
  }
  return refreshPromise
}

/**
 * Shared wrapper for every dms-core call that needs the caller's session
 * cookie - always sends `credentials: "include"`. Replaces the ~30
 * individual `fetch(...); if (!response.ok) throw new Error(...)` blocks
 * that used to be duplicated across lib/users/api.ts, lib/tenants/api.ts,
 * lib/auth/use-current-user.ts, stores/roles-store.ts,
 * stores/permissions-store.ts, and a few components - each of those threw
 * a bare `Error` that discarded the HTTP status the moment it was thrown,
 * which made a global "the session expired" handler impossible. This
 * throws `ApiError` (see lib/api-error.ts) instead, so `error.status`
 * survives all the way up to `components/providers/providers.tsx`'s
 * global `QueryCache`/`MutationCache` `onError`.
 *
 * Also the single place a lapsed-but-refreshable session is silently
 * recovered: dms-core's access-token cookie is deliberately short-lived
 * (see AuthCookieService's doc comment), so an actively-used session will
 * routinely hit a 401 here well before the user does anything that looks
 * like "logging back in." On a 401 from anything other than one of
 * dms-core's own auth endpoints, this attempts exactly one
 * refresh-and-retry (`refreshSession` above) before falling through to the
 * normal error handling below - so a genuinely expired refresh token (or a
 * refresh endpoint that itself 401s/403s/errors) still throws the original
 * 401 and reaches the existing `isSessionExpired` → clear-cache →
 * `/session-expired` flow in `providers.tsx`, unchanged. The retried
 * request's own response (success or failure) is never re-checked for
 * refresh-worthiness, which is what keeps this to at most one refresh
 * attempt per call - no retry loop is possible.
 *
 * `fallbackMessage` is used only when the response body has no JSON
 * `message` field (or isn't JSON at all) - matches every call site's
 * previous `body?.message ?? fallback` convention.
 *
 * A network failure (fetch() itself throwing - e.g. the backend is
 * unreachable) gets `status: 0`, distinguishable from a real HTTP status.
 */
export async function apiFetch(
  input: string,
  init: RequestInit = {},
  fallbackMessage = "Something went wrong. Please try again."
): Promise<Response> {
  let response = await fetchWithCredentials(input, init)

  if (response.status === 401 && !isAuthEndpoint(input) && (await refreshSession())) {
    response = await fetchWithCredentials(input, init)
  }

  if (!response.ok) {
    const body = await response.json().catch(() => null)
    throw new ApiError(response.status, body?.message ?? fallbackMessage, body?.code)
  }
  return response
}

/** `apiFetch` plus `.json()`, for the common case where the caller just wants the parsed body. */
export async function apiFetchJson<T>(
  input: string,
  init?: RequestInit,
  fallbackMessage?: string
): Promise<T> {
  const response = await apiFetch(input, init, fallbackMessage)
  return response.json() as Promise<T>
}

/** Byte progress and cancellation for `apiUpload`. */
export interface UploadTransfer {
  /** Bytes of the request body sent so far, out of `total`. */
  onProgress?: (loaded: number, total: number) => void
  /** Aborting it cancels the upload - the promise rejects with an AbortError. */
  signal?: AbortSignal
}

/**
 * `apiFetchJson` for a multipart upload that reports its progress. fetch()
 * can't tell how much of a request body has been sent, so this uses
 * XMLHttpRequest - with the same session cookie, client-type header,
 * one refresh-and-retry on 401 and ApiError on failure as `apiFetch`.
 */
export async function apiUpload<T>(
  input: string,
  body: FormData,
  transfer: UploadTransfer = {},
  fallbackMessage = "Upload failed."
): Promise<T> {
  let result = await sendWithProgress(input, body, transfer)
  if (
    result.status === 401 &&
    !isAuthEndpoint(input) &&
    (await refreshSession())
  ) {
    result = await sendWithProgress(input, body, transfer)
  }
  if (result.status < 200 || result.status >= 300) {
    throw new ApiError(
      result.status,
      result.body?.message ?? fallbackMessage,
      result.body?.code
    )
  }
  return result.body as T
}

interface UploadResult {
  status: number
  body: { message?: string; code?: string } | null
}

function sendWithProgress(
  input: string,
  body: FormData,
  transfer: UploadTransfer
): Promise<UploadResult> {
  return new Promise((resolve, reject) => {
    if (transfer.signal?.aborted) {
      reject(new DOMException("The upload was cancelled.", "AbortError"))
      return
    }
    const xhr = new XMLHttpRequest()
    xhr.open("POST", input)
    xhr.withCredentials = true
    xhr.setRequestHeader(CLIENT_TYPE_HEADER, CLIENT_TYPE)
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable)
        transfer.onProgress?.(event.loaded, event.total)
    }
    xhr.onload = () => {
      let parsed: UploadResult["body"] = null
      try {
        parsed = xhr.responseText ? JSON.parse(xhr.responseText) : null
      } catch {
        // not JSON - the fallback message is used
      }
      resolve({ status: xhr.status, body: parsed })
    }
    xhr.onerror = () => reject(new ApiError(0, NETWORK_ERROR_MESSAGE))
    xhr.onabort = () =>
      reject(new DOMException("The upload was cancelled.", "AbortError"))
    transfer.signal?.addEventListener("abort", () => xhr.abort(), {
      once: true,
    })
    xhr.send(body)
  })
}
