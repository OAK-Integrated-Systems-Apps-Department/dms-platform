"use client"

import Link from "next/link"
import { notFound, useParams } from "next/navigation"
import { AlertTriangle, ChevronLeft, Loader2 } from "lucide-react"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { RoleDetails } from "@/components/roles/role-details"
import { useRole } from "@/components/roles/use-role"
import { usePermissions } from "@/lib/auth/use-permissions"
import { usePageTitle } from "@/lib/use-page-title"

/**
 * Role Details / Assigned Users (R4) — read-only view of a role's
 * definition, permission summary, and assigned users.
 *
 * Uses `useRole` rather than reading straight off the store, since
 * landing here directly (a bookmark, a refresh) can happen before the
 * Role List's `fetchRoles()` has ever run — see the hook's own comment.
 */
export default function RoleDetailsPage() {
  const params = useParams<{ roleId: string }>()
  const { role, loading, notFound: roleNotFound } = useRole(params.roleId)
  const { hasPermission, hasAnyPermission } = usePermissions()

  usePageTitle(role ? role.name : "Role Details")

  if (roleNotFound) {
    notFound()
  }

  return (
    <DashboardShell initialActiveLabel="Users & Roles">
      <AdminBreadcrumb
        className="mb-3"
        segments={[
          { label: "Dashboard", href: "/" },
          { label: "Roles", href: "/admin/roles" },
          { label: role?.name ?? "Role Details" },
        ]}
      />
      <div className="mb-[22px] max-w-[680px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">
          Role Details
        </h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Definition, permissions, and users currently assigned to this role.
        </p>
      </div>

      <PermissionGate permission={["asm.manage-roles-permissions.view-role", "asm.manage-roles-permissions.update-role"]}>
      {loading ? (
        <div className="flex items-center justify-center gap-2 rounded-[10px] border border-border bg-card py-16 text-[13.5px] text-muted-foreground shadow-[var(--ledger-shadow)]">
          <Loader2 size={16} className="animate-spin" />
          Loading role…
        </div>
      ) : role ? (
        <RoleDetails
          role={role}
          canEdit={hasPermission("asm.manage-roles-permissions.update-role")}
          canDelete={hasPermission("asm.manage-roles-permissions.delete-role")}
          canManagePermissions={hasAnyPermission(
            "asm.manage-roles-permissions.view-role",
            "asm.manage-roles-permissions.assign-permissions"
          )}
        />
      ) : (
        <div className="flex flex-col items-center gap-2 rounded-[10px] border border-border bg-card py-16 text-center text-[13.5px] text-muted-foreground shadow-[var(--ledger-shadow)]">
          <AlertTriangle size={20} className="text-[var(--rust)]" />
          Couldn&apos;t load this role.
        </div>
      )}
      </PermissionGate>
    </DashboardShell>
  )
}