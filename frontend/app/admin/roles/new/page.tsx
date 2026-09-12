"use client"

import { useRouter } from "next/navigation"
import { toast } from "sonner"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { RoleForm } from "@/components/roles/role-form"
import type { RoleFormValues } from "@/components/roles/schema"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePageTitle } from "@/lib/use-page-title"
import { useRolesStore } from "@/stores/roles-store"

/** Add Role (R2) — create a role and choose the permissions it grants. */
export default function AddRolePage() {
  usePageTitle("Add Role")

  const router = useRouter()
  const addRole = useRolesStore((state) => state.addRole)

  async function handleSubmit(values: RoleFormValues): Promise<string | null> {
    const { error } = await addRole(values)
    if (error) {
      toast.error(error)
      return error
    }
    toast.success(`Role "${values.name}" created.`)
    router.push("/admin/roles")
    return null
  }

  return (
    <DashboardShell initialActiveLabel="Users & Roles">
      <AdminBreadcrumb
        className="mb-3"
        segments={[
          { label: "Dashboard", href: "/" },
          { label: "Roles", href: "/admin/roles" },
          { label: "Add Role" },
        ]}
      />
      <div className="mb-[22px] max-w-[680px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">
          Add Role
        </h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Create a role and choose the permissions it grants. New roles apply
          to the current tenant only.
        </p>
      </div>
      {/* addRole (stores/roles-store.ts) calls both POST /api/v1/roles
          (create-role) and, for any permissions pre-selected in the quick
          picker, syncRolePermissions (assign-permissions) - gate on
          either. */}
      <PermissionGate permission={[PermissionCodes.ASM_ROLES_CREATE_ROLE, PermissionCodes.ASM_ROLES_ASSIGN_PERMISSIONS]}>
        <RoleForm mode="create" onSubmit={handleSubmit} />
      </PermissionGate>
    </DashboardShell>
  )
}