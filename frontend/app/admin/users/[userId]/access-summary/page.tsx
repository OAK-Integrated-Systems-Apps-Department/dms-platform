"use client"

import Link from "next/link"
import { notFound, useParams } from "next/navigation"
import { ChevronLeft } from "lucide-react"

import { DashboardShell } from "@/components/layout/dashboard-shell"
import { UserAccessSummary } from "@/components/users/access-summary"
import { useUsersStore } from "@/stores/users-store"

/**
 * User Access Summary (A1) — administrator view of what a user can
 * actually access: roles held plus the effective, merged permission set.
 * Nested under `app/admin/users/[userId]/`, matching the wireframe's URL
 * and this project's existing dynamic-segment convention
 * (`app/admin/roles/[roleId]/...`).
 */
export default function UserAccessSummaryPage() {
  const params = useParams<{ userId: string }>()
  const user = useUsersStore((state) =>
    state.users.find((u) => u.id === params.userId)
  )

  if (!user) {
    notFound()
  }

  return (
    <DashboardShell initialActiveLabel="Users & Roles">
      <div className="mx-auto w-full max-w-[760px]">
        <Link
          href="/admin/roles"
          className="mb-3.5 inline-flex items-center gap-1 text-[12.5px] font-semibold text-muted-foreground hover:text-foreground"
        >
          <ChevronLeft size={14} />
          Back to Role List
        </Link>
        <div className="mb-[22px]">
          <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">
            User Access Summary
          </h1>
          <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
            What {user.name} can actually access, computed across every role
            they hold.
          </p>
        </div>
        <UserAccessSummary user={user} />
      </div>
    </DashboardShell>
  )
}
