"use client"

import { notFound, useParams } from "next/navigation"
import { AlertTriangle } from "lucide-react"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { Badge } from "@/components/ui/badge"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { LoadingState } from "@/components/ui/loading-state"
import { usePermission } from "@/components/permissions/use-permission"
import { PermissionCodes } from "@/lib/auth/permission-codes"

/**
 * View Permission — read-only retrieval of a single permission's details.
 * Permissions are a fixed, platform-managed set (each one maps 1:1 to an
 * `@PreAuthorize` check in the backend — see `PermissionController`,
 * which restricts create/update/delete to PLATFORM_ADMIN), so this screen
 * is reference-only: no Edit/Delete actions, and no raw `permission_code`
 * or shared/custom distinction surfaced — just the name, description, and
 * where it sits in the Module/Action taxonomy, same fields as the
 * Permission List.
 */
export default function PermissionDetailsPage() {
  const params = useParams<{ permissionId: string }>()
  const { permission, loading, notFound: permissionNotFound } = usePermission(
    params.permissionId
  )

  if (permissionNotFound) {
    notFound()
  }

  return (
    <DashboardShell initialActiveLabel="Permissions">
      <AdminBreadcrumb
        className="mb-3"
        segments={[
          { label: "Dashboard", href: "/" },
          { label: "Permissions", href: "/admin/permissions" },
          { label: permission?.name ?? "Permission Details" },
        ]}
      />
      <div className="mb-[22px]">
          <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">
            Permission Details
          </h1>
          <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
            Definition of a single permission in the platform-wide catalog.
          </p>
        </div>

        {loading ? (
          <LoadingState message="Loading permission…" />
        ) : (
        <PermissionGate permission={[PermissionCodes.LEGACY_ROLE_VIEW, PermissionCodes.LEGACY_ROLE_MANAGE]}>
        {permission ? (
          <div className="rounded-lg border border-border bg-card px-[30px] py-[26px] shadow-[var(--ledger-shadow)]">
            <h2 className="mb-6 font-heading text-[20px] font-semibold">
              {permission.name}
            </h2>

            <p className="mb-6 text-[13.5px] leading-[1.6] text-foreground">
              {permission.description || "No description provided."}
            </p>

            <hr className="my-6 border-dashed border-border" />

            <h3 className="mb-2.5 text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
              Module &amp; Action
            </h3>
            <div className="flex flex-wrap gap-2">
              <Badge variant="outline" className="font-mono text-[12px]">
                Module: {permission.moduleName}
              </Badge>
              <Badge variant="outline" className="font-mono text-[12px]">
                Action: {permission.actionName}
              </Badge>
            </div>
          </div>
        ) : (
          <div className="flex flex-col items-center gap-2 rounded-md border border-border bg-card py-16 text-center text-[13.5px] text-muted-foreground shadow-[var(--ledger-shadow)]">
            <AlertTriangle size={20} className="text-[var(--rust)]" />
            Couldn&apos;t load this permission.
          </div>
        )}
        </PermissionGate>
        )}
    </DashboardShell>
  )
}
