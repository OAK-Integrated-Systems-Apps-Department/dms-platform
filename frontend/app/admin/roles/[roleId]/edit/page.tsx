"use client"

import { notFound, useParams, useRouter } from "next/navigation"
import { AlertTriangle, Loader2 } from "lucide-react"
import { toast } from "sonner"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { RoleForm } from "@/components/roles/role-form"
import type { RoleFormValues } from "@/components/roles/schema"
import { useRole } from "@/components/roles/use-role"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePageTitle } from "@/lib/use-page-title"
import { useRolesStore } from "@/stores/roles-store"

/** Edit Role (R2, edit mode) — update a role's details and permissions. */
export default function EditRolePage() {
  const router = useRouter()
  const params = useParams<{ roleId: string }>()
  const { role, loading, notFound: roleNotFound } = useRole(params.roleId)
  const updateRole = useRolesStore((state) => state.updateRole)
  const syncRolePermissions = useRolesStore((state) => state.syncRolePermissions)

  usePageTitle(role ? `Edit ${role.name}` : "Edit Role")

  if (roleNotFound) {
    notFound()
  }

  async function handleSubmit(values: RoleFormValues): Promise<string | null> {
    if (!role) return "Role not loaded yet."
    const { error } = await updateRole(role.id, values)
    if (error) {
      toast.error(error)
      return error
    }
    // Name/description/status and permissions are two separate API calls
    // (UpdateRoleRequest carries no permissions field — see
    // stores/roles-store.ts) - both need to succeed for the inline
    // picker's changes to actually stick.
    const { error: permError } = await syncRolePermissions(role.id, values.permissions)
    if (permError) {
      toast.error(permError)
      return permError
    }
    toast.success(`Role "${values.name}" updated.`)
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
          {
            label: role?.name ?? "Role Details",
            href: role ? `/admin/roles/${role.id}` : undefined,
          },
          { label: "Edit" },
        ]}
      />
      <div className="mb-[22px] max-w-[680px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">
          Edit Role
        </h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          {role
            ? <>Update {role.name}&apos;s description, status, and permissions.</>
            : "Update this role's description, status, and permissions."}
        </p>
      </div>

      {loading ? (
        <div className="flex items-center justify-center gap-2 rounded-lg border border-border bg-card py-16 text-[13.5px] text-muted-foreground shadow-[var(--ledger-shadow)]">
          <Loader2 size={16} className="animate-spin" />
          Loading role…
        </div>
      ) : role ? (
        // This form calls both updateRole (update-role) and
        // syncRolePermissions (assign-permissions) - gate on either.
        <PermissionGate permission={[PermissionCodes.ASM_ROLES_UPDATE_ROLE, PermissionCodes.ASM_ROLES_ASSIGN_PERMISSIONS]}>
          <RoleForm mode="edit" role={role} onSubmit={handleSubmit} />
        </PermissionGate>
      ) : (
        <div className="flex flex-col items-center gap-2 rounded-lg border border-border bg-card py-16 text-center text-[13.5px] text-muted-foreground shadow-[var(--ledger-shadow)]">
          <AlertTriangle size={20} className="text-[var(--rust)]" />
          Couldn&apos;t load this role.
        </div>
      )}
    </DashboardShell>
  )
}