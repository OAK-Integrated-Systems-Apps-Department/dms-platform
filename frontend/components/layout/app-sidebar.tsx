"use client"

import { useEffect, useRef, useState } from "react"
import Link from "next/link"
import { usePathname } from "next/navigation"
import { useTheme } from "next-themes"
import {
  ChevronRight,
  Layers,
  Moon,
  PenLine,
  Settings,
  Sun,
  X,
} from "lucide-react"

import { cn } from "@/lib/utils"
import { APP_NAME } from "@/lib/app-config"
import type { NavGroup, NavItem } from "@/components/dashboard/types"
import { navSections } from "@/components/dashboard/nav-config"
import {
  filterNavSections,
  findActiveHref,
  groupHasHref,
} from "@/components/dashboard/nav-utils"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { useEditingConfig } from "@/lib/checkout/use-checkout"
import { useIsPlatformAdmin } from "@/lib/auth/use-is-platform-admin"
import { useWatermarkAvailability } from "@/lib/watermarks/use-watermark-availability"
import { useOcrAvailability } from "@/lib/documents/use-ocr-availability"
import { useIsTenantAdmin } from "@/lib/auth/use-is-tenant-admin"

import { useMyTaskCount } from "@/lib/workflow/use-my-task-count"
import { useUnreadCount } from "@/lib/notifications/use-notifications"
import { Dropdown } from "@/components/ui/dropdown"
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible"
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
  SidebarMenuSub,
  SidebarMenuSubButton,
  SidebarMenuSubItem,
  useSidebar,
} from "@/components/ui/sidebar"
import { Toggle } from "@/components/ui/toggle"

const OPEN_GROUPS_KEY = "dms.sidebar.open-groups"

const ACTIVE_ITEM_CLASS =
  "bg-sidebar-primary font-semibold text-sidebar-primary-foreground hover:bg-sidebar-primary hover:text-sidebar-primary-foreground"

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
  const { isPlatformAdmin } = useIsPlatformAdmin()
  // The two are mutually exclusive as the backend reports them: /me/platform-roles
  // includes PLATFORM_ADMIN, while the per-user lookup behind useIsTenantAdmin
  // deliberately excludes it - so `adminOnly` items have to check both.
  const { isTenantAdmin } = useIsTenantAdmin()

  // Live badge for the My Tasks item (see NavItem.liveBadge).
  const { data: taskCount } = useMyTaskCount()
  const unreadNotifications = useUnreadCount().data ?? 0
  const liveBadges: Record<
    NonNullable<NavItem["liveBadge"]>,
    number | undefined
  > = {
    // Tasks to decide plus information requests waiting on me (Phase 2).
    myOpenTasks:
      (taskCount ? taskCount.open + (taskCount.requestsForMe ?? 0) : 0) ||
      undefined,
    // Unread notifications in the inbox (3.2).
    myUnreadNotifications: unreadNotifications || undefined,
  }

  // Cached probe shared with the rest of the app; `undefined` while it is
  // still in flight, which keeps a feature-gated item hidden until the
  // answer arrives rather than flashing a link that may not belong here.
  const watermarking = useWatermarkAvailability().data?.present === true
  const ocr = useOcrAvailability().data === true
  // Shared with the document screens ("Edit online"): the platform administrator has allowed it for this organisation.
  // Not asked for a platform administrator (no organisation) or for someone who may not read documents.
  const onlineEditing =
    useEditingConfig(
      !isPlatformAdmin && hasAnyPermission(PermissionCodes.DOCM_DOCUMENTS_VIEW)
    ).data?.allowedByPlatform === true

  function featureOn(feature: NavItem["feature"]): boolean {
    if (!feature) return true
    if (feature === "watermarking") return watermarking
    if (feature === "ocr") return ocr
    if (feature === "onlineEditing") return onlineEditing
    return false
  }

  // Items with no `permission` set (Dashboard, Search, Audit Log, ...) are
  // always visible; items that name one are hidden entirely — not shown
  // disabled or left to 403 on click — for anyone who can't satisfy it,
  // same reasoning as every action-button gate elsewhere (see
  // `usePermissions`). An item may also name a `feature`, which has to be
  // switched on for this deployment as well. Sections that end up with zero
  // visible items are dropped too, rather than rendering an empty group
  // header.
  const visibleSections = filterNavSections(
    navSections,
    (item) =>
      (!item.platformAdminOnly || isPlatformAdmin) &&
      featureOn(item.feature) &&
      (!item.adminOnly || isPlatformAdmin || isTenantAdmin) &&
      (!item.platformAdminOrPermission ||
        isPlatformAdmin ||
        hasAnyPermission(item.platformAdminOrPermission)) &&
      (!item.permission ||
        hasAnyPermission(
          ...(Array.isArray(item.permission)
            ? item.permission
            : [item.permission])
        ))
  )

  // Only one item may read as active: the longest href matching the path, so
  // /documents/scan does not also light up "My Documents".
  const activeHref = findActiveHref(visibleSections, pathname)

  // Which collapsible groups are open, by label. A group the person has not
  // touched is open only while it holds the current page; navigating into a
  // group always opens it, and the choice is remembered per browser.
  const { state: sidebarState, isMobile } = useSidebar()
  const iconRail = sidebarState === "collapsed" && !isMobile
  // Read from storage up front rather than in an effect: every group is
  // permission- or feature-gated, so none renders until client-side data
  // arrives and there is no server markup to mismatch.
  const [openGroups, setOpenGroups] = useState<Record<string, boolean>>(() => {
    if (typeof window === "undefined") return {}
    try {
      return JSON.parse(window.localStorage.getItem(OPEN_GROUPS_KEY) ?? "{}")
    } catch {
      return {} // Storage unavailable or corrupt: the defaults are fine.
    }
  })
  const [seenHref, setSeenHref] = useState(activeHref)

  if (seenHref !== activeHref) {
    setSeenHref(activeHref)
    const holder = visibleSections
      .flatMap((section) => section.groups)
      .find((group) => groupHasHref(group, activeHref))
    if (holder) setOpenGroups((prev) => ({ ...prev, [holder.label]: true }))
  }

  useEffect(() => {
    try {
      window.localStorage.setItem(OPEN_GROUPS_KEY, JSON.stringify(openGroups))
    } catch {
      // Not persisted this time; the in-memory state still works.
    }
  }, [openGroups])

  function renderItemContent(item: NavItem, isActive: boolean) {
    const badge = item.liveBadge ? liveBadges[item.liveBadge] : item.badge
    const Icon = item.icon
    return (
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
  }

  function renderItem(item: NavItem) {
    const isActive = item.href
      ? item.href === activeHref
      : item.label === activeLabel
    const className = cn(
      "h-auto py-[9px] text-[13.5px]",
      isActive && ACTIVE_ITEM_CLASS
    )
    return (
      <SidebarMenuItem key={item.label}>
        {item.href ? (
          <SidebarMenuButton
            isActive={isActive}
            tooltip={item.label}
            render={<Link href={item.href} />}
            className={className}
          >
            {renderItemContent(item, isActive)}
          </SidebarMenuButton>
        ) : (
          <SidebarMenuButton
            isActive={isActive}
            tooltip={item.label}
            onClick={() => onNavigate?.(item.label)}
            className={className}
          >
            {renderItemContent(item, isActive)}
          </SidebarMenuButton>
        )}
      </SidebarMenuItem>
    )
  }

  function renderGroup(group: NavGroup) {
    // The icon rail has no room for a nested list (SidebarMenuSub is hidden
    // there), so the group's items are shown as plain icon buttons instead.
    if (iconRail) return group.items.map(renderItem)

    const hasActive = groupHasHref(group, activeHref)
    const open = openGroups[group.label] ?? hasActive
    const Icon = group.icon
    return (
      <SidebarMenuItem key={group.label}>
        <Collapsible
          open={open}
          onOpenChange={(next) =>
            setOpenGroups((prev) => ({ ...prev, [group.label]: next }))
          }
        >
          <CollapsibleTrigger
            className={cn(
              "flex h-auto w-full items-center gap-2 rounded-btn p-2 py-[9px] text-left text-[13.5px] ring-sidebar-ring outline-hidden hover:bg-sidebar-accent hover:text-sidebar-accent-foreground focus-visible:ring-2",
              hasActive && !open && "font-semibold"
            )}
          >
            <Icon size={16.5} className="shrink-0 opacity-85" />
            <span className="flex-1">{group.label}</span>
            <ChevronRight
              size={14}
              className={cn(
                "shrink-0 opacity-70 transition-transform",
                open && "rotate-90"
              )}
            />
          </CollapsibleTrigger>
          <CollapsibleContent>
            {/* Right margin and inner padding trimmed from the primitive's
                defaults so a sub-item's highlight reaches the same right edge
                as the parent rows and sits close to the guide line. The text
                still lines up with the parent label (px-1.5 + px-3 = the
                default's px-2.5 + px-2). */}
            <SidebarMenuSub className="mr-0 px-1.5">
              {group.items.map((item) => {
                const isActive = item.href === activeHref
                return (
                  <SidebarMenuSubItem key={item.label}>
                    <SidebarMenuSubButton
                      isActive={isActive}
                      render={<Link href={item.href ?? "#"} />}
                      className={cn(
                        "h-auto px-3 py-[7px] text-[13px]",
                        isActive && ACTIVE_ITEM_CLASS
                      )}
                    >
                      <span className="flex-1">{item.label}</span>
                    </SidebarMenuSubButton>
                  </SidebarMenuSubItem>
                )
              })}
            </SidebarMenuSub>
          </CollapsibleContent>
        </Collapsible>
      </SidebarMenuItem>
    )
  }

  const [settingsOpen, setSettingsOpen] = useState(false)
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
              {section.items.map(renderItem)}
              {section.groups.map(renderGroup)}
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
              // Beside the rail on desktop so it never covers the nav list;
              // the mobile drawer has no room to its right, so there it
              // still opens upward over the list.
              className={cn(
                "top-auto right-auto text-foreground",
                isMobile
                  ? "bottom-[52px] left-0"
                  : "bottom-0 left-full ml-3"
              )}
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

              <Link
                href="/profile#e-signature"
                onClick={() => setSettingsOpen(false)}
                className="mt-1 flex items-center gap-2 border-t border-border px-3.5 py-2.5 text-[13px] hover:bg-muted"
              >
                <PenLine size={15} className="text-muted-foreground" />
                Manage digital signature
              </Link>
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
            <span className="group-data-[collapsible=icon]:hidden">
              Settings
            </span>
          </button>
        </div>
      </SidebarFooter>
    </Sidebar>
  )
}
