"use client"

import Link from "next/link"
import { ChevronLeft } from "lucide-react"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { PermissionMatrix } from "@/components/roles/permission-matrix"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePageTitle } from "@/lib/use-page-title"

/**
 * Permission Matrix (R5) — administrative Roles × Permissions grid.
 * Static route, sibling of the `[roleId]` dynamic segment; Next.js resolves
 * `/admin/roles/matrix` against this literal folder before it would ever
 * match `[roleId]`, so no route conflict.
 *
 * Unlike the Add/Edit Role and Role Details pages, this one does NOT wrap
 * everything in a `max-w-[760px]` form-width column — the matrix is a wide
 * data table, not a form, and centering it in a narrow column left big
 * empty gutters on both sides. Only the heading/description block keeps a
 * readable `max-w-[680px]`, same as the Role List page does for its own
 * heading above the full-width `RolesTable`.
 */
export default function PermissionMatrixPage() {
  usePageTitle("Permission Matrix")

  return (
    <DashboardShell initialActiveLabel="Users & Roles">
      <AdminBreadcrumb
        className="mb-3"
        segments={[
          { label: "Dashboard", href: "/" },
          { label: "Roles", href: "/admin/roles" },
          { label: "Permission Matrix" },
        ]}
      />
      <div className="mb-[22px] max-w-[680px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">
          Permission Matrix
        </h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          See which role can perform each DMS action, grouped by functional
          area.
        </p>
      </div>
      {/* PermissionMatrix reads roles (GET /api/v1/roles, changed to the
          granular codes below) AND the permission catalog (GET
          /api/v1/permissions, still PermissionController - unchanged,
          still role.view/role.manage - see usePermissionCatalog's own
          gate). This route guard only covers the roles half; a caller
          who fails the catalog's own internal gate still sees an empty
          matrix rather than a 403 here. */}
      <PermissionGate permission={[PermissionCodes.ASM_ROLES_VIEW_ROLE, PermissionCodes.ASM_ROLES_UPDATE_ROLE]}>
        <PermissionMatrix />
      </PermissionGate>
    </DashboardShell>
  )
}
