"use client"

import { useState, type ReactNode } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"

import { ThemeProvider } from "@/components/theme-provider"
import { Toaster } from "@/components/ui/sonner"
import { TooltipProvider } from "@/components/ui/tooltip"
import { SidebarCookieProvider } from "@/components/providers/sidebar-cookie-provider"

/**
 * All client-only providers for the app, in one place. Split out of
 * app/layout.tsx so that layout.tsx itself can be a Server Component —
 * that's required to read the `sidebar_state` cookie with next/headers
 * and hand it down as `initialSidebarOpen` here, which is what lets the
 * sidebar render with the correct open/collapsed state on the very first
 * paint instead of a client-only cookie read causing a hydration
 * mismatch (see components/ui/sidebar.tsx for the consuming side).
 */
export function Providers({
  initialSidebarOpen,
  children,
}: {
  initialSidebarOpen: boolean
  children: ReactNode
}) {
  const [queryClient] = useState(() => new QueryClient())

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