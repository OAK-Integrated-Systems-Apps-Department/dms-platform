"use client"

import { useState, type ReactNode } from "react"
import { useRouter } from "next/navigation"
import { MutationCache, QueryCache, QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { toast } from "sonner"

import { ThemeProvider } from "@/components/theme-provider"
import { Toaster } from "@/components/ui/sonner"
import { TooltipProvider } from "@/components/ui/tooltip"
import { SidebarCookieProvider } from "@/components/providers/sidebar-cookie-provider"
import { isSessionExpired } from "@/lib/api-error"

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
 */
export function Providers({
  initialSidebarOpen,
  children,
}: {
  initialSidebarOpen: boolean
  children: ReactNode
}) {
  const router = useRouter()
  const [queryClient] = useState(() => {
    // Dedupes near-simultaneous 401s from concurrent requests failing
    // together the moment a session lapses (a dashboard can easily have
    // several queries in flight at once) - reset shortly after rather
    // than left permanently `true`, so a later, genuinely new expiry
    // (after signing back in) isn't silently suppressed for the rest of
    // the tab's lifetime.
    let sessionExpiredHandled = false
    let client: QueryClient

    function handlePossibleSessionExpiry(error: unknown) {
      if (!isSessionExpired(error) || sessionExpiredHandled) return
      sessionExpiredHandled = true
      setTimeout(() => {
        sessionExpiredHandled = false
      }, 5000)

      // Every cached query result (current user, permissions, lists,
      // everything) is now stale/unreachable — clear it rather than risk
      // briefly showing authenticated data that no longer applies.
      client.clear()
      toast.error("Your session has expired.")
      router.push("/session-expired")
    }

    client = new QueryClient({
      queryCache: new QueryCache({ onError: handlePossibleSessionExpiry }),
      mutationCache: new MutationCache({ onError: handlePossibleSessionExpiry }),
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