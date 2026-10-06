"use client"

import type { CSSProperties } from "react"
import Link from "next/link"
import type { LucideIcon } from "lucide-react"
import {
  FolderOpen,
  ScrollText,
  Search,
  ShieldAlert,
  Trash2,
  UsersRound,
} from "lucide-react"

import { IconTile } from "@/components/ui/icon-tile"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"

/** The top bar's search box (components/layout/topbar-search.tsx). */
export const GLOBAL_SEARCH_ID = "global-search"

interface QuickAction {
  label: string
  hint: string
  icon: LucideIcon
  color: string
  /** A page to go to - or `onSelect` for something on this one. */
  href?: string
  onSelect?: () => void
  /** Any one of these lets the user see it - the same codes as the sidebar's. */
  permissions: string[]
}

const ACTIONS: QuickAction[] = [
  {
    label: "Search",
    hint: "Find any document",
    icon: Search,
    color: "var(--primary)",
    onSelect: () => document.getElementById(GLOBAL_SEARCH_ID)?.focus(),
    permissions: [PermissionCodes.DOCM_DOCUMENTS_VIEW],
  },
  {
    label: "My Documents",
    hint: "Browse your folders",
    icon: FolderOpen,
    color: "#2b579a",
    href: "/documents",
    permissions: [PermissionCodes.DOCM_DOCUMENTS_VIEW],
  },
  {
    label: "Shared with me",
    hint: "From other people",
    icon: UsersRound,
    color: "#8764b8",
    href: "/shared",
    permissions: [PermissionCodes.DOCM_DOCUMENTS_VIEW],
  },
  {
    label: "Recycle bin",
    hint: "Restore deleted files",
    icon: Trash2,
    color: "#4f7a8a",
    href: "/recycle-bin",
    permissions: [
      PermissionCodes.RBR_RESTORE,
      PermissionCodes.RBR_PURGE,
      PermissionCodes.RBR_RETENTION,
    ],
  },
  {
    label: "Quarantine",
    hint: "Review blocked files",
    icon: ShieldAlert,
    color: "var(--rust)",
    href: "/admin/quarantine",
    permissions: [
      PermissionCodes.DOCM_QUARANTINE_VIEW,
      PermissionCodes.DOCM_QUARANTINE_RELEASE,
      PermissionCodes.DOCM_QUARANTINE_DELETE,
      PermissionCodes.DOCM_QUARANTINE_OVERRIDE,
    ],
  },
  {
    label: "Audit log",
    hint: "Who did what, when",
    icon: ScrollText,
    color: "var(--gold)",
    href: "/admin/audit-log",
    permissions: [PermissionCodes.ABDR_VIEW_AUDIT_HISTORY],
  },
]

const tileClass =
  "group flex h-full w-full items-center gap-3 rounded-xl border border-border bg-card px-3.5 py-3 text-left shadow-[var(--ledger-shadow)] transition-[transform,border-color,box-shadow] duration-200 hover:-translate-y-0.5 hover:border-[var(--border-strong)] hover:shadow-[0_10px_24px_-14px_rgba(0,0,0,0.35)] focus-visible:ring-2 focus-visible:ring-ring/50 focus-visible:outline-none"

/** Shortcuts to the places people go most - only the ones the user may open. */
export function QuickActions() {
  const { hasAnyPermission } = usePermissions()
  const actions = ACTIONS.filter((a) => hasAnyPermission(...a.permissions))
  if (actions.length === 0) return null

  return (
    <nav aria-label="Quick actions" className="mb-5">
      <ul className="grid grid-cols-2 gap-3 sm:grid-cols-[repeat(auto-fit,minmax(180px,1fr))]">
        {actions.map((action, i) => {
          const content = (
            <>
              <IconTile
                icon={action.icon}
                color={action.color}
                size={38}
                className="transition-transform duration-200 group-hover:scale-105"
              />
              <span className="min-w-0">
                <span className="block text-[13px] leading-tight font-semibold sm:truncate">
                  {action.label}
                </span>
                <span className="hidden truncate text-[11.5px] text-muted-foreground sm:block">
                  {action.hint}
                </span>
              </span>
            </>
          )
          return (
            <li
              key={action.label}
              className="dash-rise"
              style={{ "--dash-order": i + 5 } as CSSProperties}
            >
              {action.href ? (
                <Link href={action.href} className={tileClass}>
                  {content}
                </Link>
              ) : (
                <button
                  type="button"
                  onClick={action.onSelect}
                  className={tileClass}
                >
                  {content}
                </button>
              )}
            </li>
          )
        })}
      </ul>
    </nav>
  )
}
