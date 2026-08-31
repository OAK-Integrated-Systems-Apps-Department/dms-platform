"use client"

import Link from "next/link"
import { notFound, useParams } from "next/navigation"
import { ChevronLeft } from "lucide-react"

import { DashboardShell } from "@/components/layout/dashboard-shell"
import { UserSessions } from "@/components/users/user-sessions"
import { useUsersStore } from "@/stores/users-store"

/**
 * User Sessions (U9) — active login sessions for a user, with per-session
 * and revoke-all controls. Nested under `app/admin/users/[userId]/`,
 * matching the wireframe's URL (`.../admin/users/1024/sessions`) and this
 * project's existing dynamic-segment convention
 * (`app/admin/users/[userId]/mfa`, `.../access-summary`).
 */
export default function UserSessionsPage() {
  const params = useParams<{ userId: string }>()
  const user = useUsersStore((state) =>
    state.users.find((u) => u.id === params.userId)
  )
  const revokeSession = useUsersStore((state) => state.revokeSession)
  const revokeAllSessions = useUsersStore((state) => state.revokeAllSessions)

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
            User Sessions
          </h1>
          <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
            View {user.name}&apos;s active sessions and terminate one or all of
            them where required.
          </p>
        </div>
        <UserSessions
          user={user}
          onRevokeSession={(sessionId) => revokeSession(user.id, sessionId)}
          onRevokeAllSessions={() => revokeAllSessions(user.id)}
        />
      </div>
    </DashboardShell>
  )
}
