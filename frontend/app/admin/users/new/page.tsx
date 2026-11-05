"use client"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { UserInviteForm } from "@/components/users/user-invite-form"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePageTitle } from "@/lib/use-page-title"

/** Add User — invite a new user into the current tenant. Admin-only; not self-registration. */
export default function AddUserPage() {
  usePageTitle("Add User")

  return (
    <DashboardShell initialActiveLabel="Users & Roles">
      <AdminBreadcrumb
        className="mb-3"
        segments={[
          { label: "Dashboard", href: "/" },
          { label: "Users", href: "/admin/users" },
          { label: "Add User" },
        ]}
      />
      <div className="mb-[22px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">Add User</h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Invite a new user into the current tenant. They&apos;ll get an email to verify their address
          and set their own password and there&apos;s no self-registration in this app.
        </p>
      </div>
      {/* This form calls both POST /api/v1/users (create) and, when an
          initial role is picked, POST /api/v1/users/{userId}/roles
          (assign-role) - gate on either, matching the two backend actions
          it can trigger. */}
      <PermissionGate permission={[PermissionCodes.OTUA_USERS_CREATE, PermissionCodes.OTUA_USERS_ASSIGN_ROLE]}>
        <UserInviteForm />
      </PermissionGate>
    </DashboardShell>
  )
}