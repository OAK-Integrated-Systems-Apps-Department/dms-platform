"use client"

import type { ReactNode } from "react"
import { AlertTriangle, Loader2 } from "lucide-react"

import { usePermissions } from "@/lib/auth/use-permissions"

interface PermissionGateProps {
  /**
   * The permission code(s) required to see this page's content. An array
   * is "any of" — matches how the backend's own `@PreAuthorize` checks
   * are written (e.g. `role.view` OR `role.manage`).
   */
  permission: string | string[]
  children: ReactNode
  /** Shown instead of the default access-denied message, when set. */
  message?: string
}

/**
 * Route-level guard for admin pages that are purely action-based (Add
 * User, Edit Role, and similar) — the entry-point link into these is
 * already hidden from anyone without the permission (see each page's own
 * list/detail screen), but a direct URL visit needs its own check too,
 * since hiding a link is only ever a UX nicety, not enforcement.
 * <p>
 * This is a frontend-only convenience: the real enforcement is still the
 * backend's own `@PreAuthorize` on the endpoint this page's form submits
 * to, unchanged by anything here. This exists purely so someone without
 * the permission sees an explicit "you can't do this" instead of a form
 * that renders fine and then 403s on submit.
 * <p>
 * While `usePermissions()` is still loading, renders a spinner rather
 * than either the content or the denied state, so a permitted user
 * doesn't see a flash of "access denied" before their real permission
 * set arrives.
 */
export function PermissionGate({ permission, children, message }: PermissionGateProps) {
  const { hasAnyPermission, isLoading } = usePermissions()
  const codes = Array.isArray(permission) ? permission : [permission]
  const allowed = hasAnyPermission(...codes)

  if (isLoading) {
    return (
      <div className="flex items-center justify-center gap-2 rounded-[14px] border border-border bg-card py-16 text-[13.5px] text-muted-foreground shadow-[var(--ledger-shadow)]">
        <Loader2 size={16} className="animate-spin" />
        Loading…
      </div>
    )
  }

  if (!allowed) {
    return (
      <div className="flex flex-col items-center gap-2 rounded-[14px] border border-border bg-card py-16 text-center text-[13.5px] text-muted-foreground shadow-[var(--ledger-shadow)]">
        <AlertTriangle size={20} className="text-[var(--rust)]" />
        {message ?? "You don't have permission to view this page."}
      </div>
    )
  }

  return <>{children}</>
}
