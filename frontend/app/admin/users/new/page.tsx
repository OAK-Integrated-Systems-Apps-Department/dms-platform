"use client"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { UserInviteForm } from "@/components/users/user-invite-form"
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
      <div className="mb-[22px] max-w-[680px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">Add User</h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Invite a new user into the current tenant. They&apos;ll get an email to verify their address
          and set their own password there&apos;s no self-registration in this app.
        </p>
      </div>
      <UserInviteForm />
    </DashboardShell>
  )
}