"use client"

import { useState, type ReactNode } from "react"

import { SidebarInset, SidebarProvider } from "@/components/ui/sidebar"

import { AppSidebar } from "./app-sidebar"
import { AppTopbar } from "./app-topbar"

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
 * The sidebar's own open/collapsed state now lives inside SidebarProvider
 * (components/ui/sidebar.tsx) — it persists across page loads via a
 * cookie and responds to Cmd/Ctrl+B, both for free. This shell no longer
 * tracks that state itself; AppTopbar's <SidebarTrigger> and AppSidebar
 * both read it from context via useSidebar().
 */
export function DashboardShell({
  children,
  initialActiveLabel = "My Documents",
}: DashboardShellProps) {
  const [activeLabel, setActiveLabel] = useState(initialActiveLabel)

  return (
    <SidebarProvider>
      <AppSidebar activeLabel={activeLabel} onNavigate={setActiveLabel} />
      <SidebarInset>
        <AppTopbar />
        <div className="flex-1 px-[30px] pt-[26px] pb-[50px]">{children}</div>
      </SidebarInset>
    </SidebarProvider>
  )
}