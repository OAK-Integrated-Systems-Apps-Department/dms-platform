"use client"

import { notFound, useParams } from "next/navigation"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { UserEditForm } from "@/components/users/user-edit-form"
import { LoadingState } from "@/components/ui/loading-state"
import { PermissionCodes } from "@/lib/auth/permission-codes"
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
        <LoadingState message="Loading user…" />
      ) : isError ? (
        <div className="rounded-lg border border-[var(--rust)] bg-[var(--rust-tint)] p-[26px] text-[13.5px] text-[var(--rust)] shadow-[var(--ledger-shadow)]">
          {error instanceof Error ? error.message : "Could not load this user."}
        </div>
      ) : (
        // This form calls both PUT /api/v1/users/{userId} (update) and, if
        // roles changed, POST/DELETE /api/v1/users/{userId}/roles
        // (assign-role) - gate on either.
        <PermissionGate permission={[PermissionCodes.OTUA_USERS_UPDATE, PermissionCodes.OTUA_USERS_ASSIGN_ROLE]}>
          <UserEditForm user={user} />
        </PermissionGate>
      )}
    </DashboardShell>
  )
}