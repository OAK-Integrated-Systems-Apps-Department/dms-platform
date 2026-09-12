"use client"

import Link from "next/link"
import { notFound, useParams } from "next/navigation"
import { ChevronLeft } from "lucide-react"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { UserAccessSummary } from "@/components/users/access-summary"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { usePageTitle } from "@/lib/use-page-title"
import { useUser } from "@/lib/users/use-user"

/**
 * User Access Summary (A1) — administrator view of what a user can
 * actually access: roles held plus the effective, merged permission set.
 * Nested under `app/admin/users/[userId]/`, matching the wireframe's URL
 * and this project's existing dynamic-segment convention
 * (`app/admin/roles/[roleId]/...`).
 */
export default function UserAccessSummaryPage() {
  const params = useParams<{ userId: string }>()
  const { data: user, isPending, isError, error, toggleStatus } = useUser(
    params.userId
  )
  const { hasPermission } = usePermissions()

  usePageTitle(user ? `${user.name} Access Summary` : "User Access Summary")

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
            { label: "Access Summary" },
          ]}
        />
        <Link
          href="/admin/users"
          className="mb-3.5 inline-flex items-center gap-1 text-[12.5px] font-semibold text-muted-foreground hover:text-foreground"
        >
          <ChevronLeft size={14} />
          Back to User List
        </Link>
        <div className="mb-[22px]">
          <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">
            User Access Summary
          </h1>
          <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
            {user
              ? `What ${user.name} can actually access, computed across every role they hold.`
              : "What this user can actually access, computed across every role they hold."}
          </p>
        </div>
        {isPending ? (
          <div className="rounded-[10px] border border-border bg-card p-[26px] text-[13.5px] text-muted-foreground shadow-[var(--ledger-shadow)]">
            Loading user…
          </div>
        ) : isError ? (
          <div className="rounded-[10px] border border-[var(--rust)] bg-[var(--rust-tint)] p-[26px] text-[13.5px] text-[var(--rust)] shadow-[var(--ledger-shadow)]">
            {error instanceof Error ? error.message : "Could not load this user."}
          </div>
        ) : (
          <UserAccessSummary
            user={user}
            onToggleStatus={toggleStatus}
            canManage={hasPermission(PermissionCodes.OTUA_USERS_ACTIVATE_DEACTIVATE)}
          />
        )}
    </DashboardShell>
  )
}