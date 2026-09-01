"use client"

import { useState, type ReactNode } from "react"

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
 * The sidebar's open/collapsed state lives here (not inside AppSidebar
 * itself) so the topbar's toggle button and the sidebar's own width both
 * react to the same piece of state.
 */
export function DashboardShell({
  children,
  initialActiveLabel = "My Documents",
}: DashboardShellProps) {
  const [activeLabel, setActiveLabel] = useState(initialActiveLabel)
  const [sidebarOpen, setSidebarOpen] = useState(true)

  return (
    <div className="flex min-h-svh bg-background text-foreground">
      <AppSidebar activeLabel={activeLabel} onNavigate={setActiveLabel} open={sidebarOpen} />
      <div className="flex min-w-0 flex-1 flex-col">
        <AppTopbar sidebarOpen={sidebarOpen} onToggleSidebar={() => setSidebarOpen((v) => !v)} />
        <main className="flex-1 px-[30px] pt-[26px] pb-[50px]">{children}</main>
      </div>
    </div>
  )
}
