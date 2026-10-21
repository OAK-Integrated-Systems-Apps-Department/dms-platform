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
  liveBadge?: "myOpenTasks" | "myUnreadNotifications"
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
  /** Only for platform administrators (cross-tenant) - e.g. managing organisations. */
  platformAdminOnly?: boolean
  /**
   * An optional feature that must be switched on for this deployment before
   * the item appears, on top of any `permission`. Needed where holding the
   * permission isn't enough because the whole module can be turned off
   * server-side: `dms.watermark.enabled=false` unmaps the endpoints, so a
   * tenant admin would otherwise keep a link to a page with nothing behind
   * it. Resolved live in `AppSidebar`; omit for the usual case, where a
   * permission alone decides.
   */
  feature?: "watermarking" | "ocr"
  /**
   * For administrators of either kind - PLATFORM_ADMIN or TENANT_ADMIN.
   * For screens that are an administrator's job but aren't tied to one
   * organisation's data, such as System Monitoring. Distinct from
   * `platformAdminOnly` (strictly cross-tenant) and from `permission`
   * (a specific capability from the catalogue); the two admin roles are
   * fixed platform roles from the caller's JWT, not catalogue entries.
   */
  adminOnly?: boolean
  /**
   * Visible to platform administrators, or to anyone holding this permission.
   * A platform administrator has no tenant and so no resolved permission list,
   * hence the separate check; tenant admins pass through `hasAnyPermission`.
   */
  platformAdminOrPermission?: string
}

/**
 * A collapsible sub-group inside a section (e.g. "Access" under
 * Administration). Its items obey the same visibility rules as any other
 * item; a group whose items are all hidden is dropped.
 */
export interface NavGroup {
  label: string
  icon: LucideIcon
  items: NavItem[]
}

export interface NavSection {
  /** Small uppercase label above the group, e.g. "Administration". Omit for the top group. */
  label?: string
  items: NavItem[]
  /** Collapsible sub-groups, rendered after `items`. */
  groups?: NavGroup[]
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
