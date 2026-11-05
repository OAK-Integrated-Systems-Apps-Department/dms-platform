"use client"

import { LoadFailed } from "@/components/ui/load-failed"
import { notFound, useParams } from "next/navigation"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { UserSessions } from "@/components/users/user-sessions"
import { LoadingState } from "@/components/ui/loading-state"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { usePageTitle } from "@/lib/use-page-title"
import { useUser } from "@/lib/users/use-user"
import { useUsersStore } from "@/stores/users-store"

/**
 * User Sessions (U9) — active login sessions for a user, with per-session
 * and revoke-all controls. Nested under `app/admin/users/[userId]/`,
 * matching the wireframe's URL (`.../admin/users/1024/sessions`) and this
 * project's existing dynamic-segment convention
 * (`app/admin/users/[userId]/mfa`, `.../access-summary`).
 *
 * The user itself now comes from dms-core (`useUser`, same as View User).
 * `revokeSession` / `revokeAllSessions` stay wired to the mock
 * `users-store` placeholders below — dms-core has no session endpoints yet
 * (that lives in Keycloak's `/admin/realms/{realm}/sessions` /
 * `.../users/{id}/logout`), so there's nothing real to call yet, and the
 * session list itself is always empty until that integration exists.
 * Swap these for real mutations once that endpoint exists.
 */
export default function UserSessionsPage() {
  const params = useParams<{ userId: string }>()
  const { data: user, isPending, isError, error } = useUser(params.userId)
  const revokeSession = useUsersStore((state) => state.revokeSession)
  const revokeAllSessions = useUsersStore((state) => state.revokeAllSessions)
  const { hasPermission } = usePermissions()

  usePageTitle(user ? `${user.name} Sessions` : "User Sessions")

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
            { label: "Sessions" },
          ]}
        />
        <div className="mb-[22px]">
          <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">
            User Sessions
          </h1>
          <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
            {user
              ? `View ${user.name}'s active sessions and terminate one or all of them where required.`
              : "View this user's active sessions and terminate one or all of them where required."}
          </p>
        </div>
        {isPending ? (
          <LoadingState message="Loading user…" />
        ) : isError ? (
          <LoadFailed error={error} fallback="Could not load this user." />
        ) : (
          <UserSessions
            user={user}
            onRevokeSession={(sessionId) => revokeSession(user.id, sessionId)}
            onRevokeAllSessions={() => revokeAllSessions(user.id)}
            // No dedicated granular code for session revocation - closest
            // "manage this account" equivalent to the old user.manage.
            canManage={hasPermission(PermissionCodes.OTUA_USERS_UPDATE)}
          />
        )}
    </DashboardShell>
  )
}