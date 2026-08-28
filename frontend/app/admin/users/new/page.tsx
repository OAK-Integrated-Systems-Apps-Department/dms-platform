"use client"

import { DashboardShell } from "@/components/layout/dashboard-shell"
import { UserInviteForm } from "@/components/users/user-invite-form"

/** Add User — invite a new user into the current tenant. Admin-only; not self-registration. */
export default function AddUserPage() {
  return (
    <DashboardShell initialActiveLabel="Users & Roles">
      <div className="mx-auto w-full max-w-[600px]">
        <div className="mb-[22px]">
          <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">Add User</h1>
          <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
            Invite a new user into the current tenant. They&apos;ll get an email to verify their address
            and set their own password — there&apos;s no self-registration in this app.
          </p>
        </div>
        <UserInviteForm />
      </div>
    </DashboardShell>
  )
}