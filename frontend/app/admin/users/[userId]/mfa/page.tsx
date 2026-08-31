"use client"

import Link from "next/link"
import { notFound, useParams } from "next/navigation"
import { ChevronLeft } from "lucide-react"

import { DashboardShell } from "@/components/layout/dashboard-shell"
import { UserMfaManagement } from "@/components/users/user-mfa-management"
import { useUsersStore } from "@/stores/users-store"

/**
 * User MFA Management (U8) — administrator view of a user's MFA
 * enrollment plus the permitted admin actions (require setup, reset
 * credentials). Nested under `app/admin/users/[userId]/`, matching the
 * wireframe's URL (`.../admin/users/1024/mfa`) and this project's existing
 * dynamic-segment convention (`app/admin/users/[userId]/access-summary`,
 * `app/admin/roles/[roleId]/...`).
 */
export default function UserMfaManagementPage() {
  const params = useParams<{ userId: string }>()
  const user = useUsersStore((state) =>
    state.users.find((u) => u.id === params.userId)
  )
  const requireMfaSetup = useUsersStore((state) => state.requireMfaSetup)
  const resetMfaCredentials = useUsersStore(
    (state) => state.resetMfaCredentials
  )

  if (!user) {
    notFound()
  }

  return (
    <DashboardShell initialActiveLabel="Users & Roles">
      <div className="mx-auto w-full max-w-[760px]">
        <Link
          href={`/admin/users/${user.id}`}
          className="mb-3.5 inline-flex items-center gap-1 text-[12.5px] font-semibold text-muted-foreground hover:text-foreground"
        >
          <ChevronLeft size={14} />
          Back to View User
        </Link>
        <div className="mb-[22px]">
          <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">
            User MFA Management
          </h1>
          <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
            View {user.name}&apos;s multi-factor authentication status and
            perform permitted administrative actions such as requiring MFA setup
            or resetting registered credentials.
          </p>
        </div>
        <UserMfaManagement
          user={user}
          onRequireSetup={() => requireMfaSetup(user.id)}
          onResetCredentials={() => resetMfaCredentials(user.id)}
        />
      </div>
    </DashboardShell>
  )
}
