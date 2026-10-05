import type { LucideIcon } from "lucide-react"

/**
 * Shared types for the dashboard feature. Keeping these in one file means
 * every component and every mock/real data source speaks the same shape.
 */

export interface NavItem {
  label: string
  icon: LucideIcon
  badge?: number
  /**
   * A live count to show as the badge instead of a static `badge` -
   * resolved by `AppSidebar` itself (e.g. "myOpenTasks" = the caller's
   * open workflow tasks, polled from /workflow/tasks/my/count). Hidden
   * while zero or still loading.
   */
  liveBadge?: "myOpenTasks"
  /**
   * Real route to navigate to (e.g. "/admin/roles"). When present, the
   * sidebar renders this item as a Next.js `Link` and derives its active
   * state from the current pathname instead of `activeLabel`. Omit for
   * items that don't have a page yet — they keep the original
   * `onNavigate`-only demo behavior.
   */
  href?: string
  /**
   * Permission code(s) required to see this item at all — an array is
   * "any of", matching the backend's own `@PreAuthorize` checks (e.g.
   * `role.view` OR `role.manage`). Checked via `usePermissions()` in
   * `AppSidebar`. Omit for items that don't correspond to a
   * permission-gated backend resource (e.g. "Dashboard", "Search") —
   * those stay visible to everyone.
   */
  permission?: string | string[]
}

export interface NavSection {
  /** Small uppercase label above the group, e.g. "Governance". Omit for the top group. */
  label?: string
  items: NavItem[]
}

export interface AppUser {
  name: string
  role: string
  initials: string
}

export type DocumentFileType = "pdf" | "xlsx" | "docx" | "folder"

export type DocumentStatus = "Approved" | "Pending" | "Draft" | "Rejected"

export interface RecentDocument {
  id: string
  name: string
  type: DocumentFileType
  owner: string
  modified: string
  size: string
  status: DocumentStatus
}

export interface ActivityEntry {
  id: string
  who: string
  action: string
  target: string
  time: string
  icon: LucideIcon
}
