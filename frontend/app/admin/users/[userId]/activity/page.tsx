"use client"

import Link from "next/link"
import { notFound, useParams } from "next/navigation"
import { ChevronLeft } from "lucide-react"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { UserAuditHistory } from "@/components/users/user-audit-history"
import { usePageTitle } from "@/lib/use-page-title"
import { useUser } from "@/lib/users/use-user"

/**
 * User Activity / Audit History (U10) — filterable, paginated feed of
 * security and administrative activity for a user. Nested under
 * `app/admin/users/[userId]/`, matching the wireframe's URL
 * (`.../admin/users/1024/activity`) and this project's existing
 * dynamic-segment convention (`app/admin/users/[userId]/mfa`,
 * `.../sessions`, `.../access-summary`).
 *
 * The user itself now comes from dms-core (`useUser`, same as View User).
 * `recentActivity` is always empty until an audit-log endpoint exists on
 * the backend — see `lib/users/api.ts`'s `toUserRecord` for the same gap.
 */
export default function UserAuditHistoryPage() {
  const params = useParams<{ userId: string }>()
  const { data: user, isPending, isError, error } = useUser(params.userId)

  usePageTitle(user ? `${user.name} Activity` : "User Activity")

  if (isError && error instanceof Error && error.message.includes("not found")) {
    notFound()
  }

  return (
    <DashboardShell initialActiveLabel="Users & Roles">
        <AdminBreadcrumb
          className="mb-3"
          segments={[
            { label: "Dashboard", href: "/" },
            { label: "Users", href: "/admin/users" },
            {
              label: user?.name ?? "View User",
              href: user ? `/admin/users/${user.id}` : undefined,
            },
            { label: "Activity" },
          ]}
        />
        <Link
          href={user ? `/admin/users/${user.id}` : "/admin/users"}
          className="mb-3.5 inline-flex items-center gap-1 text-[12.5px] font-semibold text-muted-foreground hover:text-foreground"
        >
          <ChevronLeft size={14} />
          Back to View User
        </Link>
        <div className="mb-[22px]">
          <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">
            User Activity / Audit History
          </h1>
          <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
            {user
              ? `Security and administrative activity for ${user.name} — login, failed login, role changes, activation/deactivation, password reset, and other significant account events.`
              : "Security and administrative activity for this user — login, failed login, role changes, activation/deactivation, password reset, and other significant account events."}
          </p>
        </div>
        {isPending ? (
          <div className="rounded-md border border-border bg-card p-[26px] text-[13.5px] text-muted-foreground shadow-[var(--ledger-shadow)]">
            Loading user…
          </div>
        ) : isError ? (
          <div className="rounded-md border border-[var(--rust)] bg-[var(--rust-tint)] p-[26px] text-[13.5px] text-[var(--rust)] shadow-[var(--ledger-shadow)]">
            {error instanceof Error ? error.message : "Could not load this user."}
          </div>
        ) : (
        <UserAuditHistory user={user} />
        )}
    </DashboardShell>
  )
}