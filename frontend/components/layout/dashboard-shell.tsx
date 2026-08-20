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
 */
export function DashboardShell({
  children,
  initialActiveLabel = "My Documents",
}: DashboardShellProps) {
  const [activeLabel, setActiveLabel] = useState(initialActiveLabel)

  return (
    <div className="flex min-h-svh bg-background text-foreground">
      <AppSidebar activeLabel={activeLabel} onNavigate={setActiveLabel} />
      <div className="flex min-w-0 flex-1 flex-col">
        <AppTopbar />
        <main className="flex-1 px-[30px] pt-[26px] pb-[50px]">{children}</main>
      </div>
    </div>
  )
}
