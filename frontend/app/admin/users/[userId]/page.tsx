"use client"

import { notFound } from "next/navigation"
import { useParams } from "next/navigation"

import { DashboardShell } from "@/components/layout/dashboard-shell"
import { UserDetailView } from "@/components/users/user-detail-view"
import { useUsersStore } from "@/stores/users-store"

/** View User — per dms-view-user.html. Read-only overview reached from the "View" action on the User List. */
export default function ViewUserPage() {
  const params = useParams<{ userId: string }>()
  const user = useUsersStore((state) => state.users.find((u) => u.id === params.userId))
  const toggleStatus = useUsersStore((state) => state.toggleStatus)

  if (!user) {
    notFound()
  }

  return (
    <DashboardShell initialActiveLabel="Users & Roles">
      <div className="mx-auto w-full max-w-[820px]">
        <div className="mb-[22px]">
          <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">View User</h1>
          <p className="max-w-[640px] text-[13.5px] leading-[1.6] text-muted-foreground">
            Read-only overview of {user.name}&apos;s profile, account status, assigned roles, security
            information, and recent account activity.
          </p>
        </div>
        <UserDetailView user={user} onToggleStatus={() => toggleStatus(user.id)} />
      </div>
    </DashboardShell>
  )
}