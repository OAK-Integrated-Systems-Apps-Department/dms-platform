import { ApiError } from "@/lib/api-error"

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
  let response: Response
  try {
    response = await fetch(input, { ...init, credentials: "include" })
  } catch {
    throw new ApiError(0, NETWORK_ERROR_MESSAGE)
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
