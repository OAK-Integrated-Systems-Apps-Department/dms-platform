"use client"

import { notFound, useParams, useRouter } from "next/navigation"

import { DashboardShell } from "@/components/layout/dashboard-shell"
import { RoleForm } from "@/components/roles/role-form"
import type { RoleFormValues } from "@/components/roles/schema"
import { useRolesStore } from "@/stores/roles-store"

/** Edit Role (R2, edit mode) — update a role's details and permissions. */
export default function EditRolePage() {
  const router = useRouter()
  const params = useParams<{ roleId: string }>()
  const role = useRolesStore((state) =>
    state.roles.find((r) => r.id === params.roleId)
  )
  const updateRole = useRolesStore((state) => state.updateRole)

  if (!role) {
    notFound()
  }

  function handleSubmit(values: RoleFormValues) {
    updateRole(role.id, values)
    router.push("/admin/roles")
  }

  return (
    <DashboardShell initialActiveLabel="Users & Roles">
      <div className="mx-auto w-full max-w-[760px]">
        <div className="mb-[22px]">
          <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">
            Edit Role
          </h1>
          <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
            Update {role.name}&apos;s description, status, and permissions.
          </p>
        </div>
        <RoleForm mode="edit" role={role} onSubmit={handleSubmit} />
      </div>
    </DashboardShell>
  )
}
