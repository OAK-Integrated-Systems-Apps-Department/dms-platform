"use client"

import { useRouter } from "next/navigation"

import { DashboardShell } from "@/components/layout/dashboard-shell"
import { PermissionForm } from "@/components/permissions/permission-form"
import type { PermissionFormValues } from "@/components/permissions/schema"
import { usePermissionsStore } from "@/stores/permissions-store"

/** Add Permission — create a new entry in the platform-wide permission catalog. */
export default function AddPermissionPage() {
  const router = useRouter()
  const addPermission = usePermissionsStore((state) => state.addPermission)

  async function handleSubmit(values: PermissionFormValues): Promise<string | null> {
    const { error } = await addPermission(values)
    if (error) return error
    router.push("/admin/permissions")
    return null
  }

  return (
    <DashboardShell initialActiveLabel="Permissions">
      <div className="mx-auto w-full max-w-[760px]">
        <div className="mb-[22px]">
          <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">
            Add Permission
          </h1>
          <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
            Add a new grantable permission to the catalog, and choose the
            module and action it belongs to. Every tenant's roles will be
            able to draw on it once it's created.
          </p>
        </div>
        <PermissionForm mode="create" onSubmit={handleSubmit} />
      </div>
    </DashboardShell>
  )
}
