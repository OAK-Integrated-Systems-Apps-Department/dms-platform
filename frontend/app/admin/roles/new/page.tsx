"use client"

import { useRouter } from "next/navigation"

import { DashboardShell } from "@/components/layout/dashboard-shell"
import { RoleForm } from "@/components/roles/role-form"
import type { RoleFormValues } from "@/components/roles/schema"
import { useRolesStore } from "@/stores/roles-store"

/** Add Role (R2) — create a role and choose the permissions it grants. */
export default function AddRolePage() {
  const router = useRouter()
  const addRole = useRolesStore((state) => state.addRole)

  function handleSubmit(values: RoleFormValues) {
    addRole(values)
    router.push("/admin/roles")
  }

  return (
    <DashboardShell initialActiveLabel="Users & Roles">
      <div className="mx-auto w-full max-w-[760px]">
        <div className="mb-[22px]">
          <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">
            Add Role
          </h1>
          <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
            Create a role and choose the permissions it grants. New roles apply
            to the current tenant only.
          </p>
        </div>
        <RoleForm mode="create" onSubmit={handleSubmit} />
      </div>
    </DashboardShell>
  )
}
