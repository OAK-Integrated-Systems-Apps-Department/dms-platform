"use client"

import { useEffect, useRef, useState } from "react"
import Link from "next/link"
import { usePathname } from "next/navigation"
import { useTheme } from "next-themes"
import {
  Layers,
  Moon,
  PenTool,
  Settings,
  ShieldCheck,
  Sun,
  X,
} from "lucide-react"

import { cn } from "@/lib/utils"
import { APP_NAME } from "@/lib/app-config"
import type { NavItem } from "@/components/dashboard/types"
import { navSections } from "@/components/dashboard/nav-config"
import { usePermissions } from "@/lib/auth/use-permissions"
import { useMyTaskCount } from "@/lib/workflow/use-my-task-count"
import { Dropdown } from "@/components/ui/dropdown"
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
} from "@/components/ui/sidebar"
import { Toggle } from "@/components/ui/toggle"

interface AppSidebarProps {
  activeLabel?: string
  onNavigate?: (label: string) => void
}

/**
 * Left navigation rail — always the dark "vault" chrome, in both app
 * themes (the --sidebar-* CSS variables in globals.css already matched
 * this component's expectations before this rewrite, so the look didn't
 * need to change). Reads its items from `nav-config.ts`.
 *
 * `collapsible="icon"` is the real shadcn Sidebar's own collapse mode —
 * it shrinks to an icon-only rail rather than fully hiding, persists the
 * open/closed state in a cookie, and responds to Cmd/Ctrl+B, all via
 * SidebarProvider (see dashboard-shell.tsx). SidebarMenuButton's
 * `tooltip` prop automatically shows the item's label on hover ONLY while
 * collapsed — nothing extra to wire up for that.
 *
 * The Settings popover at the bottom controls dark mode (via next-themes)
 * plus two demo toggles — kept as the existing custom Dropdown rather
 * than converted to a Sidebar-specific primitive, since its content
 * (toggle rows, a settings sub-link) doesn't map onto SidebarMenu*
 * components anyway. It previously got visually clipped by the old
 * sidebar's `overflow-hidden` (added for the width-collapse transition
 * that's now gone) — the real Sidebar's collapsed panel doesn't clip its
 * own contents the same way, which is what fixes that.
 */
export function AppSidebar({
  activeLabel = "My Documents",
  onNavigate,
}: AppSidebarProps) {
  const { resolvedTheme, setTheme } = useTheme()
  const isDark = resolvedTheme === "dark"
  const pathname = usePathname()
  const { hasAnyPermission } = usePermissions()
  // Live badge for the My Tasks item (see NavItem.liveBadge).
  const { data: taskCount } = useMyTaskCount()
  const liveBadges: Record<NonNullable<NavItem["liveBadge"]>, number | undefined> = {
    // Tasks to decide plus information requests waiting on me (Phase 2).
    myOpenTasks: (taskCount ? taskCount.open + (taskCount.requestsForMe ?? 0) : 0) || undefined,
  }

  // Items with no `permission` set (Dashboard, Search, Audit Log, ...) are
  // always visible; items that name one are hidden entirely — not shown
  // disabled or left to 403 on click — for anyone who can't satisfy it,
  // same reasoning as every action-button gate elsewhere (see
  // `usePermissions`). Sections that end up with zero visible items are
  // dropped too, rather than rendering an empty group header.
  const visibleSections = navSections
    .map((section) => ({
      ...section,
      items: section.items.filter(
        (item) =>
          !item.permission ||
          hasAnyPermission(
            ...(Array.isArray(item.permission) ? item.permission : [item.permission])
          )
      ),
    }))
    .filter((section) => section.items.length > 0)

  const [settingsOpen, setSettingsOpen] = useState(false)
  const [requireSignature, setRequireSignature] = useState(true)
  const [autoLock, setAutoLock] = useState(false)
  const settingsRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    function handlePointerDown(event: MouseEvent) {
      if (
        settingsRef.current &&
        !settingsRef.current.contains(event.target as Node)
      ) {
        setSettingsOpen(false)
      }
    }
    document.addEventListener("mousedown", handlePointerDown)
    return () => document.removeEventListener("mousedown", handlePointerDown)
  }, [])

  return (
    <Sidebar collapsible="icon">
      <SidebarHeader>
        <div className="flex items-center gap-2.5 py-1">
          <div className="flex size-[34px] shrink-0 items-center justify-center rounded-lg border border-white/15 bg-sidebar-primary group-data-[collapsible=icon]:size-8">
            <Layers size={18} color="var(--sidebar-primary-foreground)" />
          </div>
          <div className="group-data-[collapsible=icon]:hidden">
            <div className="font-heading text-[17px] leading-none font-semibold">
              {APP_NAME}
            </div>
            <div className="mt-0.5 text-[10.5px] tracking-[0.08em] text-[var(--sidebar-muted)]">
              DOCUMENT VAULT
            </div>
          </div>
        </div>
      </SidebarHeader>

      <SidebarContent>
        {visibleSections.map((section, index) => (
          <SidebarGroup key={section.label ?? `section-${index}`}>
            {section.label && (
              <SidebarGroupLabel className="text-[10.5px] tracking-[0.08em] text-[var(--sidebar-muted)] uppercase">
                {section.label}
              </SidebarGroupLabel>
            )}
            <SidebarMenu>
              {section.items.map((item: NavItem) => {
                const isActive = item.href
                  ? item.href === "/"
                    ? pathname === "/"
                    : pathname.startsWith(item.href)
                  : item.label === activeLabel
                const Icon = item.icon
                const badge = item.liveBadge ? liveBadges[item.liveBadge] : item.badge
                const content = (
                  <>
                    <Icon size={16.5} className={cn(!isActive && "opacity-85")} />
                    <span className="flex-1">{item.label}</span>
                    {badge != null && (
                      <span className="rounded-full bg-[var(--gold)] px-1.5 py-px text-[10.5px] font-bold text-[#221a0c]">
                        {badge}
                      </span>
                    )}
                  </>
                )

                return (
                  <SidebarMenuItem key={item.label}>
                    {item.href ? (
                      <SidebarMenuButton
                        isActive={isActive}
                        tooltip={item.label}
                        render={<Link href={item.href} />}
                        className={cn(
                          "h-auto py-[9px] text-[13.5px]",
                          isActive &&
                            "bg-sidebar-primary font-semibold text-sidebar-primary-foreground hover:bg-sidebar-primary hover:text-sidebar-primary-foreground"
                        )}
                      >
                        {content}
                      </SidebarMenuButton>
                    ) : (
                      <SidebarMenuButton
                        isActive={isActive}
                        tooltip={item.label}
                        onClick={() => onNavigate?.(item.label)}
                        className={cn(
                          "h-auto py-[9px] text-[13.5px]",
                          isActive &&
                            "bg-sidebar-primary font-semibold text-sidebar-primary-foreground hover:bg-sidebar-primary hover:text-sidebar-primary-foreground"
                        )}
                      >
                        {content}
                      </SidebarMenuButton>
                    )}
                  </SidebarMenuItem>
                )
              })}
            </SidebarMenu>
          </SidebarGroup>
        ))}
      </SidebarContent>

      <SidebarFooter>
        <div ref={settingsRef} className="relative">
          {settingsOpen && (
            <Dropdown
              width={260}
              noPad
              className="top-auto right-auto bottom-[52px] left-0 text-foreground"
            >
              <div className="flex items-center justify-between p-3.5 pb-2.5">
                <span className="text-[13.5px] font-semibold">Settings</span>
                <button
                  type="button"
                  onClick={() => setSettingsOpen(false)}
                  className="text-[var(--ink-faint)]"
                >
                  <X size={15} />
                </button>
              </div>

              <div className="flex items-center justify-between px-3.5 py-2">
                <div className="flex items-center gap-2">
                  {isDark ? (
                    <Moon size={15} className="text-muted-foreground" />
                  ) : (
                    <Sun size={15} className="text-muted-foreground" />
                  )}
                  <span className="text-[13px]">Dark mode</span>
                </div>
                <Toggle
                  checked={isDark}
                  onChange={(checked) => setTheme(checked ? "dark" : "light")}
                  label="Toggle dark mode"
                />
              </div>

              <div className="flex items-center justify-between px-3.5 py-2">
                <div className="flex items-center gap-2">
                  <PenTool size={15} className="text-muted-foreground" />
                  <span className="text-[13px]">
                    Require e-signature on approval
                  </span>
                </div>
                <Toggle
                  checked={requireSignature}
                  onChange={setRequireSignature}
                  label="Require e-signature on approval"
                />
              </div>

              <div className="flex items-center justify-between px-3.5 py-2">
                <div className="flex items-center gap-2">
                  <ShieldCheck size={15} className="text-muted-foreground" />
                  <span className="text-[13px]">Auto-lock signed documents</span>
                </div>
                <Toggle
                  checked={autoLock}
                  onChange={setAutoLock}
                  label="Auto-lock signed documents"
                />
              </div>

              <button
                type="button"
                className="mt-2.5 w-full rounded-lg border border-border bg-muted px-2.5 py-2 text-left text-[12.5px]"
              >
                Manage signature certificates →
              </button>
            </Dropdown>
          )}

          <button
            type="button"
            onClick={() => setSettingsOpen((value) => !value)}
            title="Settings"
            className={cn(
              "flex w-full items-center gap-2.5 rounded-lg border border-white/[0.08] px-2.5 py-[9px] text-[13.5px]",
              "group-data-[collapsible=icon]:justify-center group-data-[collapsible=icon]:px-0",
              settingsOpen ? "bg-sidebar-primary" : "bg-transparent"
            )}
          >
            <Settings size={16.5} className="shrink-0" />
            <span className="group-data-[collapsible=icon]:hidden">Settings</span>
          </button>
        </div>
      </SidebarFooter>
    </Sidebar>
  )
}