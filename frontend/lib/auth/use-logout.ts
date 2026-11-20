"use client"

import { useCallback, useRef, useState } from "react"
import { useRouter } from "next/navigation"
import { useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import { ApiError } from "@/lib/api-error"
import { API_BASE_URL } from "@/lib/api-config"
import { apiFetch, cancelIntentionalLogout, markIntentionalLogout } from "@/lib/api-fetch"
import { ROUTES } from "@/lib/app-config"

/** Shown when the server answered with an error: it is known that the logout did not go through. */
export const LOGOUT_FAILED_MESSAGE = "Could not sign out. Please try again."

/**
 * Shown when the request never got an answer (network failure). The server may have cleared the session before the
 * reply was lost, so the person is told neither "you are signed in" nor "you are signed out" - only that it is not
 * confirmed, and that trying again is safe (logging out twice is harmless: dms-core's logout is idempotent).
 */
export const LOGOUT_UNCONFIRMED_MESSAGE =
  "Could not confirm that you were signed out. Check your connection and try again."

const LOGOUT_TOAST_ID = "logout-failed"

/**
 * The account menu's "Log out" (FE-M3).
 *
 * The session lives in httpOnly cookies that only dms-core can clear, so a logout that did not reach it leaves the
 * person signed in - and `proxy.ts` sends anyone with an access cookie from `/login` straight back to `/`. The old
 * action ignored every failure and navigated anyway, so a failed logout looked like a successful one that immediately
 * signed the person back in. Now:
 *
 * - success: clear the cached "me"-scoped data, go to `/login`, refresh (as before);
 * - failure (network error or any non-2xx): say so, stay where you are, keep the cached data, and let the person try
 *   again - there is no automatic retry, and a second request happens only after another click;
 * - while a request is pending, further clicks do nothing (a ref, so two clicks in the same tick cannot both pass).
 *
 * The "intentional logout" flag (lib/api-fetch.ts) is set before the request, so a background 401 that lands while the
 * cookies are being cleared is not reported as an expired session. It is cleared again on failure, so a failed logout
 * does not hide a genuine session expiry for the rest of its window. (If the server did clear the session and only the
 * reply was lost, the next request gets a 401 and the normal expired-session flow takes over - which is accurate.)
 */
export function useLogout() {
  const router = useRouter()
  const queryClient = useQueryClient()
  const inFlight = useRef(false)
  const [pending, setPending] = useState(false)

  const logout = useCallback(async () => {
    if (inFlight.current) return
    inFlight.current = true
    setPending(true)

    markIntentionalLogout()
    try {
      await apiFetch(`${API_BASE_URL}/api/v1/auth/logout`, { method: "POST" })
    } catch (error) {
      cancelIntentionalLogout()
      toast.error(error instanceof ApiError && error.status === 0 ? LOGOUT_UNCONFIRMED_MESSAGE : LOGOUT_FAILED_MESSAGE, {
        id: LOGOUT_TOAST_ID,
      })
      inFlight.current = false
      setPending(false)
      return
    }

    // The same reasoning as login-page-content.tsx's onSuccess: a client-side navigation never remounts the Providers
    // tree that owns the React Query cache, so without this whoever signs in next in this tab would start out seeing
    // this person's cached data. Only now - a failed logout keeps the person's data on screen. The guard stays set:
    // the page is on its way out, and a second request now would be pointless.
    queryClient.clear()
    router.push(ROUTES.LOGIN)
    router.refresh()
  }, [queryClient, router])

  return { logout, pending }
}