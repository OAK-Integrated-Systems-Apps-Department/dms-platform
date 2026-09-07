"use client"

import { notFound, useParams } from "next/navigation"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { UserEditForm } from "@/components/users/user-edit-form"
import { usePageTitle } from "@/lib/use-page-title"
import { useUser } from "@/lib/users/use-user"

/** Edit User — per dms-edit-user.html. Reached from the "Edit" action on the User List or the View User screen. */
export default function EditUserPage() {
  const params = useParams<{ userId: string }>()
  const { data: user, isPending, isError, error } = useUser(params.userId)

  usePageTitle(user ? `Edit ${user.name}` : "Edit User")

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
          { label: "Edit" },
        ]}
      />
      <div className="mb-[22px] max-w-[680px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">Edit User</h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Maintain permitted user profile information such as name, email, and other administrative
          attributes. Identity-related fields follow the Keycloak integration rules.
        </p>
      </div>
      {isPending ? (
        <div className="rounded-[14px] border border-border bg-card p-[26px] text-[13.5px] text-muted-foreground shadow-[var(--ledger-shadow)]">
          Loading user…
        </div>
      ) : isError ? (
        <div className="rounded-[14px] border border-[var(--rust)] bg-[var(--rust-tint)] p-[26px] text-[13.5px] text-[var(--rust)] shadow-[var(--ledger-shadow)]">
          {error instanceof Error ? error.message : "Could not load this user."}
        </div>
      ) : (
      <UserEditForm user={user} />
      )}
    </DashboardShell>
  )
}