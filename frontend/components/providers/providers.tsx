"use client"

import { useState, type ReactNode } from "react"
import { useRouter } from "next/navigation"
import {
  MutationCache,
  QueryCache,
  QueryClient,
  QueryClientProvider,
} from "@tanstack/react-query"
import { toast } from "sonner"

import { ThemeProvider } from "@/components/theme-provider"
import { Toaster } from "@/components/ui/sonner"
import { TooltipProvider } from "@/components/ui/tooltip"
import { SidebarCookieProvider } from "@/components/providers/sidebar-cookie-provider"
import {
  isNetworkError,
  isOrganisationInactive,
  isSessionExpired,
} from "@/lib/api-error"
import { API_BASE_URL } from "@/lib/api-config"
import { isIntentionalLogout, NETWORK_ERROR_MESSAGE } from "@/lib/api-fetch"
import { useProactiveSessionRefresh } from "@/lib/auth/use-proactive-session-refresh"

/**
 * All client-only providers for the app, in one place. Split out of
 * app/layout.tsx so that layout.tsx itself can be a Server Component —
 * that's required to read the `sidebar_state` cookie with next/headers
 * and hand it down as `initialSidebarOpen` here, which is what lets the
 * sidebar render with the correct open/collapsed state on the very first
 * paint instead of a client-only cookie read causing a hydration
 * mismatch (see components/ui/sidebar.tsx for the consuming side).
 *
 * This is also the single global interception point for an expired
 * session: every authenticated fetch in the app goes through
 * `apiFetch`/`apiFetchJson` (lib/api-fetch.ts) via a `useQuery`/
 * `useMutation` call, so a `QueryCache`/`MutationCache`-level `onError`
 * here sees every single one of them, without each of the ~30 call sites
 * needing its own 401 handling. See `isSessionExpired`'s own doc comment
 * for why this can safely treat any 401 it observes as "the session
 * expired" (as opposed to, say, a wrong password on the login form).
 *
 * The same `onError` also toasts once per burst for a connection-level
 * failure (`isNetworkError` — backend down, network error, connection
 * refused; see its doc comment in lib/api-error.ts). Unlike the session
 * case this never redirects: a dropped connection is usually transient and
 * the user is still looking at a page that may well work again on retry,
 * so pages that want more than a toast (a full-content-area replacement)
 * render `components/ui/backend-unavailable.tsx` themselves off their own
 * query's `isError`/`error` — this handler's job is just to make sure a
 * network failure is never completely silent anywhere in the app, even on
 * a page that doesn't check `isError` at all.
 *
 * Also mounts `useProactiveSessionRefresh` (lib/auth/use-proactive-session-
 * refresh.ts), the other half of session handling: it renews the session
 * ahead of the 401s this component reacts to above, while the user is
 * actually active, so most users never hit that reactive path at all.
 */
// How long an "already handled" dedupe flag (sessionExpiredHandled /
// networkErrorHandled below) stays set before a fresh burst of failures is
// allowed to toast again.
const ERROR_DEDUPE_WINDOW_MS = 5000

export function Providers({
  initialSidebarOpen,
  children,
}: {
  initialSidebarOpen: boolean
  children: ReactNode
}) {
  const router = useRouter()
  useProactiveSessionRefresh()
  const [queryClient] = useState(() => {
    // Dedupes near-simultaneous 401s from concurrent requests failing
    // together the moment a session lapses (a dashboard can easily have
    // several queries in flight at once) - reset shortly after rather
    // than left permanently `true`, so a later, genuinely new expiry
    // (after signing back in) isn't silently suppressed for the rest of
    // the tab's lifetime.
    let sessionExpiredHandled = false
    // Same dedupe reasoning as sessionExpiredHandled above, on its own
    // flag/timer: a page with several queries in flight can fail together
    // in one connection drop, and should still only produce one toast.
    let networkErrorHandled = false

    function handleGlobalError(error: unknown) {
      // The organisation was suspended or closed mid-session: sign out and say why.
      if (isOrganisationInactive(error)) {
        if (sessionExpiredHandled) return
        sessionExpiredHandled = true
        client.clear()
        void fetch(`${API_BASE_URL}/api/v1/auth/logout`, {
          method: "POST",
          credentials: "include",
        }).catch(() => undefined)
        router.push("/login?reason=organisation-inactive")
        return
      }

      if (isSessionExpired(error)) {
        // A stray in-flight query's 401 landing right after the explicit
        // "Log out" action cleared the session cookies - that action is
        // already navigating to /login itself, so this isn't an unexpected
        // expiry and shouldn't show "Your session has expired" or redirect
        // to /session-expired instead.
        if (isIntentionalLogout()) return
        if (sessionExpiredHandled) return
        sessionExpiredHandled = true
        setTimeout(() => {
          sessionExpiredHandled = false
        }, ERROR_DEDUPE_WINDOW_MS)

        // Every cached query result (current user, permissions, lists,
        // everything) is now stale/unreachable — clear it rather than risk
        // briefly showing authenticated data that no longer applies.
        client.clear()
        toast.error("Your session has expired.", { id: "session-expired" })
        router.push("/session-expired")
        return
      }

      if (isNetworkError(error)) {
        if (networkErrorHandled) return
        networkErrorHandled = true
        setTimeout(() => {
          networkErrorHandled = false
        }, ERROR_DEDUPE_WINDOW_MS)

        // No redirect and no client.clear() here, deliberately — this is
        // very likely transient, and the user's already-loaded data/page
        // is still probably fine. Just make sure it's never silent.
        toast.error(NETWORK_ERROR_MESSAGE, {
          id: NETWORK_ERROR_MESSAGE,
          // One tap to try again, without reloading the page: re-run whatever the open page is showing.
          action: { label: "Retry", onClick: () => void client.refetchQueries({ type: "active" }) },
        })
      }
    }

    // handleGlobalError only reads `client` when a query/mutation fails,
    // by which point this const is long initialised.
    const client = new QueryClient({
      queryCache: new QueryCache({ onError: handleGlobalError }),
      mutationCache: new MutationCache({ onError: handleGlobalError }),
    })
    return client
  })

  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <TooltipProvider>
          <SidebarCookieProvider initialOpen={initialSidebarOpen}>
            {children}
          </SidebarCookieProvider>
          <Toaster position="top-right" />
        </TooltipProvider>
      </ThemeProvider>
    </QueryClientProvider>
  )
}
