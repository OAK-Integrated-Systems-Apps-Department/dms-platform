"use client"

import { notFound } from "next/navigation"
import { useParams } from "next/navigation"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { UserDetailView } from "@/components/users/user-detail-view"
import { usePageTitle } from "@/lib/use-page-title"
import { useUser } from "@/lib/users/use-user"

/** View User — per dms-view-user.html. Read-only overview reached from the "View" action on the User List. */
export default function ViewUserPage() {
  const params = useParams<{ userId: string }>()
  const { data: user, isPending, isError, error, toggleStatus, sendPasswordReset } =
    useUser(params.userId)

  usePageTitle(user ? user.name : "View User")

  // A missing user (deleted, wrong tenant, bad id) surfaces as a fetch
  // error from dms-core rather than an empty local-store lookup — route
  // it to the same not-found screen the mock store used to trigger.
  if (isError && error instanceof Error && error.message.includes("not found")) {
    notFound()
  }

  return (
    <DashboardShell initialActiveLabel="Users & Roles">
      <div className="mx-auto w-full max-w-[820px]">
        <AdminBreadcrumb
          className="mb-3"
          segments={[
            { label: "Dashboard", href: "/" },
            { label: "Users", href: "/admin/users" },
            { label: user?.name ?? "View User" },
          ]}
        />
        <div className="mb-[22px]">
          <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">
            View User
          </h1>
          <p className="max-w-[640px] text-[13.5px] leading-[1.6] text-muted-foreground">
            {user
              ? `Read-only overview of ${user.name}'s profile, account status, assigned roles, security information, and recent account activity.`
              : "Read-only overview of a user's profile, account status, assigned roles, security information, and recent account activity."}
          </p>
        </div>
        {isPending ? (
          <div className="rounded-[14px] border border-border bg-card p-[26px] text-[13.5px] text-muted-foreground shadow-[var(--ledger-shadow)]">
            Loading user…
          </div>
        ) : isError ? (
          <div className="rounded-[14px] border border-[var(--rust)] bg-[var(--rust-tint)] p-[26px] text-[13.5px] text-[var(--rust)] shadow-[var(--ledger-shadow)]">
            {error instanceof Error
              ? error.message
              : "Could not load this user."}
          </div>
        ) : (
        <UserDetailView
          user={user}
            onToggleStatus={toggleStatus}
            onResetPassword={sendPasswordReset}
        />
        )}
      </div>
    </DashboardShell>
  )
}