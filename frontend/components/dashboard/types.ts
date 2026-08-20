import type { LucideIcon } from "lucide-react"

/**
 * Shared types for the dashboard feature. Keeping these in one file means
 * every component and every mock/real data source speaks the same shape.
 */

export interface NavItem {
  label: string
  icon: LucideIcon
  badge?: number
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

export interface ApprovalTask {
  id: string
  name: string
  requester: string
  due: string
  size: string
}

export interface ActivityEntry {
  id: string
  who: string
  action: string
  target: string
  time: string
  icon: LucideIcon
}