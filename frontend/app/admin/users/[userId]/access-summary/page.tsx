"use client"

import { LoadFailed } from "@/components/ui/load-failed"
import { notFound, useParams } from "next/navigation"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { UserAccessSummary } from "@/components/users/access-summary"
import { LoadingState } from "@/components/ui/loading-state"
import { isNotFound } from "@/lib/api-error"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { USER_VIEW_PERMISSIONS } from "@/lib/auth/user-view-permissions"
import { usePermissions } from "@/lib/auth/use-permissions"
import { usePageTitle } from "@/lib/use-page-title"
import { useUser } from "@/lib/users/use-user"

/**
 * User Access Summary (A1) — administrator view of what a user can
 * actually access: roles held plus the effective, merged permission set.
 * Nested under `app/admin/users/[userId]/`, matching the wireframe's URL
 * and this project's existing dynamic-segment convention
 * (`app/admin/roles/[roleId]/...`).
 *
 * Gated like View User (FE-M4): the page is for someone holding one of
 * `USER_VIEW_PERMISSIONS`; activating or deactivating stays behind its own,
 * narrower check (`canManage`), which the page gate does not replace.
 */
export default function UserAccessSummaryPage() {
  const params = useParams<{ userId: string }>()

  return (
    <DashboardShell initialActiveLabel="Users & Roles">
      <PermissionGate permission={USER_VIEW_PERMISSIONS}>
        <UserAccessSummaryContent userId={params.userId} />
      </PermissionGate>
    </DashboardShell>
  )
}

function UserAccessSummaryContent({ userId }: { userId: string }) {
  const { data: user, isPending, isError, error, toggleStatus } = useUser(userId)
  const { hasPermission } = usePermissions()

  usePageTitle(user ? `${user.name} Access Summary` : "User Access Summary")

  if (isError && isNotFound(error)) {
    notFound()
  }

  return (
    <>
        <AdminBreadcrumb
          className="mb-3"
          segments={[
            { label: "Dashboard", href: "/" },
            { label: "Users", href: "/admin/users" },
            {
              label: user?.name ?? "View User",
              href: user ? `/admin/users/${user.id}` : undefined,
            },
            { label: "Access Summary" },
          ]}
        />
       
        <div className="mb-[22px]">
          <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">
            User Access Summary
          </h1>
          <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
            {user
              ? `What ${user.name} can actually access, computed across every role they hold.`
              : "What this user can actually access, computed across every role they hold."}
          </p>
        </div>
        {isPending ? (
          <LoadingState message="Loading user…" />
        ) : isError ? (
          <LoadFailed error={error} fallback="Could not load this user." />
        ) : (
          <UserAccessSummary
            user={user}
            onToggleStatus={toggleStatus}
            canManage={
              hasPermission(PermissionCodes.OTUA_USERS_ACTIVATE) ||
              hasPermission(PermissionCodes.OTUA_USERS_DEACTIVATE)
            }
          />
        )}
    </>
  )
}