"use client"

import { notFound, useParams, useRouter } from "next/navigation"
import Link from "next/link"
import { ChevronLeft } from "lucide-react"

import { DashboardShell } from "@/components/layout/dashboard-shell"
import { RolePermissionConfig } from "@/components/roles/role-permission-config"
import { useRolesStore } from "@/stores/roles-store"

/**
 * Role Permission Configuration (R3) — configure the permissions granted
 * by a single role, reached from the Edit Role screen's
 * "Configure Permissions" action.
 */
export default function RolePermissionsPage() {
  const router = useRouter()
  const params = useParams<{ roleId: string }>()
  const role = useRolesStore((state) =>
    state.roles.find((r) => r.id === params.roleId)
  )
  const updateRolePermissions = useRolesStore(
    (state) => state.updateRolePermissions
  )

  if (!role) {
    notFound()
  }

  function handleSave(permissions: string[]) {
    updateRolePermissions(role.id, permissions)
    router.push(`/admin/roles/${role.id}/edit`)
  }

  return (
    <DashboardShell initialActiveLabel="Users & Roles">
      <div className="mx-auto w-full max-w-[760px]">
        <Link
          href={`/admin/roles/${role.id}/edit`}
          className="mb-3.5 inline-flex items-center gap-1 text-[12.5px] font-semibold text-muted-foreground hover:text-foreground"
        >
          <ChevronLeft size={14} />
          Back to {role.name}
        </Link>
        <div className="mb-[22px]">
          <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">
            Role Permission Configuration
          </h1>
          <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
            Choose what {role.name} can do in the current tenant, grouped by
            functional area.
          </p>
        </div>
        <RolePermissionConfig role={role} onSave={handleSave} />
      </div>
    </DashboardShell>
  )
}
