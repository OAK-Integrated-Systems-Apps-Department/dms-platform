"use client"

import { notFound, useParams, useRouter } from "next/navigation"
import { AlertTriangle, Loader2 } from "lucide-react"

import { DashboardShell } from "@/components/layout/dashboard-shell"
import { RoleForm } from "@/components/roles/role-form"
import type { RoleFormValues } from "@/components/roles/schema"
import { useRole } from "@/components/roles/use-role"
import { useRolesStore } from "@/stores/roles-store"

/** Edit Role (R2, edit mode) — update a role's details and permissions. */
export default function EditRolePage() {
  const router = useRouter()
  const params = useParams<{ roleId: string }>()
  const { role, loading, notFound: roleNotFound } = useRole(params.roleId)
  const updateRole = useRolesStore((state) => state.updateRole)

  if (roleNotFound) {
    notFound()
  }

  async function handleSubmit(values: RoleFormValues): Promise<string | null> {
    if (!role) return "Role not loaded yet."
    const { error } = await updateRole(role.id, values)
    if (error) return error
    router.push("/admin/roles")
    return null
  }

  return (
    <DashboardShell initialActiveLabel="Users & Roles">
      <div className="mx-auto w-full max-w-[760px]">
        <div className="mb-[22px]">
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
          <div className="flex items-center justify-center gap-2 rounded-[14px] border border-border bg-card py-16 text-[13.5px] text-muted-foreground shadow-[var(--ledger-shadow)]">
            <Loader2 size={16} className="animate-spin" />
            Loading role…
          </div>
        ) : role ? (
          <RoleForm mode="edit" role={role} onSubmit={handleSubmit} />
        ) : (
          <div className="flex flex-col items-center gap-2 rounded-[14px] border border-border bg-card py-16 text-center text-[13.5px] text-muted-foreground shadow-[var(--ledger-shadow)]">
            <AlertTriangle size={20} className="text-[var(--rust)]" />
            Couldn&apos;t load this role.
          </div>
        )}
      </div>
    </DashboardShell>
  )
}
