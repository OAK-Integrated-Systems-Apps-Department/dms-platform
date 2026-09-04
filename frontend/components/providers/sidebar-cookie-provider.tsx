"use client"

import { createContext, useContext, type ReactNode } from "react"

const SidebarCookieContext = createContext<boolean | undefined>(undefined)

/**
 * Wrap the app with this in a Server Component (app/layout.tsx), passing
 * `initialOpen` read from the `sidebar_state` cookie via `next/headers`.
 * This is what lets the sidebar's open/collapsed state be correct on the
 * very first paint — matching what the server rendered — instead of
 * defaulting to expanded and then flipping after hydration.
 */
export function SidebarCookieProvider({
  initialOpen,
  children,
}: {
  initialOpen: boolean
  children: ReactNode
}) {
  return (
    <SidebarCookieContext.Provider value={initialOpen}>
      {children}
    </SidebarCookieContext.Provider>
  )
}

/**
 * Reads the server-provided sidebar open state. Falls back to `true` if
 * used outside a SidebarCookieProvider (e.g. a route that doesn't render
 * through the root layout for some reason) so it degrades safely.
 */
export function useSidebarCookie() {
  const value = useContext(SidebarCookieContext)
  return value ?? true
}