"use client"

import { useState, type ReactNode } from "react"

import { UploadsPanel } from "@/components/documents/uploads-panel"
import { PinAudienceSheet } from "@/components/pins/pin-audience-sheet"
import { SidebarInset, SidebarProvider } from "@/components/ui/sidebar"
import { useSidebarCookie } from "@/components/providers/sidebar-cookie-provider"

import { AppSidebar } from "./app-sidebar"
import { AppTopbar } from "./app-topbar"
import { ImpersonationBanner } from "./impersonation-banner"

interface DashboardShellProps {
  children: ReactNode
  initialActiveLabel?: string
}

/**
 * Page-level layout: sidebar + topbar + scrollable content area. Any
 * dashboard page composes with:
 *
 *   <DashboardShell>
 *     ...page content...
 *   </DashboardShell>
 *
 * The sidebar's own open/collapsed state lives inside SidebarProvider
 * (components/ui/sidebar.tsx) — it persists across page loads via a
 * cookie and responds to Cmd/Ctrl+B, both for free. This shell no longer
 * tracks that state itself; AppTopbar's <SidebarTrigger> and AppSidebar
 * both read it from context via useSidebar().
 *
 * `defaultOpen` is read from the `sidebar_state` cookie *server-side* (in
 * app/layout.tsx, via next/headers) and handed down through
 * SidebarCookieProvider/useSidebarCookie. That's required rather than
 * reading document.cookie in here or in SidebarProvider directly: this
 * component and page.tsx are both Client Components, so anything they
 * compute during the first render also runs identically during SSR
 * (where there's no `document`) — reading the cookie client-side would
 * make the server-rendered HTML and the client's first render disagree
 * and trigger a hydration mismatch.
 */
export function DashboardShell({
  children,
  initialActiveLabel = "My Documents",
}: DashboardShellProps) {
  const [activeLabel, setActiveLabel] = useState(initialActiveLabel)
  const defaultSidebarOpen = useSidebarCookie()

  return (
    <SidebarProvider defaultOpen={defaultSidebarOpen}>
      <AppSidebar activeLabel={activeLabel} onNavigate={setActiveLabel} />
      {/*
        min-w-0 is load-bearing here: without it, a wide child anywhere
        inside `children` can force this flex item — and the page — to
        grow past the viewport width, triggering page-level horizontal
        scroll. Because the Sidebar is `fixed` (see components/ui/sidebar.tsx)
        it doesn't scroll with that, so it ends up visually overlapping
        content that has shifted underneath it.

        Deliberately NOT overflow-x-hidden here: setting overflow-x
        without overflow-y makes the browser compute overflow-y as `auto`
        (an element can't be hidden on one axis and visible on the
        other), which turns this into its own scroll/clip container —
        that silently clipped AppTopbar's dropdown popouts, since they're
        descendants of this element positioned outside its box. min-w-0
        (which lets content shrink instead of overflowing) plus the
        html/body backstop in app/globals.css are enough on their own;
        any genuinely un-shrinkable wide content (tables, etc.) should
        get its own local `overflow-x-auto` container instead, as
        components/users/users-table.tsx already does.
      */}
      <SidebarInset className="min-w-0">
        <ImpersonationBanner />
        <AppTopbar />
        <div className="min-w-0 flex-1 px-[30px] pt-[26px] pb-[50px]">
          {children}
        </div>
      </SidebarInset>
      {/* Uploads in progress, bottom right - they carry on from page to page. */}
      <UploadsPanel />
      {/* Choosing who sees a pin - opened from any pin button or menu (stores/pin-audience-store.ts). */}
      <PinAudienceSheet />
    </SidebarProvider>
  )
}
