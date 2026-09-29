"use client"

import { notFound, useParams } from "next/navigation"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { UserMfaManagement } from "@/components/users/user-mfa-management"
import { LoadingState } from "@/components/ui/loading-state"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { usePageTitle } from "@/lib/use-page-title"
import { useUser } from "@/lib/users/use-user"

/**
 * User MFA Management (U8) — administrator view of a user's MFA
 * enrollment plus the permitted admin actions (require setup, reset
 * credentials). Nested under `app/admin/users/[userId]/`, matching the
 * wireframe's URL (`.../admin/users/1024/mfa`) and this project's existing
 * dynamic-segment convention (`app/admin/users/[userId]/access-summary`,
 * `app/admin/roles/[roleId]/...`).
 *
 * The user itself comes from dms-core (`useUser`, same as View User).
 * `requireMfaSetup` / `resetMfaCredentials` below are inert placeholders —
 * dms-core has no MFA-credential endpoints yet (that lives in Keycloak's
 * `/admin/realms/{realm}/users/{id}/credentials`), so there's nothing real
 * to call. Swap these for real mutations once that endpoint exists.
 */
export default function UserMfaManagementPage() {
  const params = useParams<{ userId: string }>()
  const { data: user, isPending, isError, error } = useUser(params.userId)
  const { hasPermission } = usePermissions()

  usePageTitle(user ? `${user.name} MFA` : "User MFA")

  if (isError && error instanceof Error && error.message.includes("not found")) {
    notFound()
  }

  function handleRequireSetup() {
    console.info(`MFA setup required for user ${params.userId}`)
  }

  function handleResetCredentials() {
    console.info(`MFA credentials reset for user ${params.userId}`)
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
            { label: "MFA" },
          ]}
        />
        
        <div className="mb-[22px]">
          <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">
            User MFA Management
          </h1>
          <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
            {user
              ? `View ${user.name}'s multi-factor authentication status and perform permitted administrative actions such as requiring MFA setup or resetting registered credentials.`
              : "View this user's multi-factor authentication status and perform permitted administrative actions such as requiring MFA setup or resetting registered credentials."}
          </p>
        </div>
        {isPending ? (
          <LoadingState message="Loading user…" />
        ) : isError ? (
          <div className="rounded-md border border-[var(--rust)] bg-[var(--rust-tint)] p-[26px] text-[13.5px] text-[var(--rust)] shadow-[var(--ledger-shadow)]">
            {error instanceof Error ? error.message : "Could not load this user."}
          </div>
        ) : (
          <UserMfaManagement
            user={user}
            onRequireSetup={handleRequireSetup}
            onResetCredentials={handleResetCredentials}
            // No dedicated granular code for MFA admin actions in the
            // catalog - OTUA_USERS_UPDATE is the closest "manage this
            // account" equivalent to the old user.manage.
            canManage={hasPermission(PermissionCodes.OTUA_USERS_UPDATE)}
          />
        )}
    </DashboardShell>
  )
}