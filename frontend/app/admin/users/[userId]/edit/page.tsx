"use client"

import { notFound, useParams } from "next/navigation"

import { DashboardShell } from "@/components/layout/dashboard-shell"
import { UserEditForm } from "@/components/users/user-edit-form"
import { useUsersStore } from "@/stores/users-store"

/** Edit User — per dms-edit-user.html. Reached from the "Edit" action on the User List or the View User screen. */
export default function EditUserPage() {
  const params = useParams<{ userId: string }>()
  const user = useUsersStore((state) => state.users.find((u) => u.id === params.userId))

  if (!user) {
    notFound()
  }

  return (
    <DashboardShell initialActiveLabel="Users & Roles">
      <div className="mx-auto w-full max-w-[760px]">
        <div className="mb-[22px]">
          <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">Edit User</h1>
          <p className="max-w-[620px] text-[13.5px] leading-[1.6] text-muted-foreground">
            Maintain permitted user profile information such as name, email, and other administrative
            attributes. Identity-related fields follow the Keycloak integration rules.
          </p>
        </div>
        <UserEditForm user={user} />
      </div>
    </DashboardShell>
  )
}