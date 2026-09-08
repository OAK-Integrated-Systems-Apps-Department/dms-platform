"use client"

import { notFound, useParams, useRouter } from "next/navigation"
import { AlertTriangle, Loader2 } from "lucide-react"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { PermissionForm } from "@/components/permissions/permission-form"
import type { PermissionFormValues } from "@/components/permissions/schema"
import { usePermission } from "@/components/permissions/use-permission"
import { usePermissionsStore } from "@/stores/permissions-store"

/** Edit Permission — update a permission's name, description, and module/action. Its code is fixed once created. */
export default function EditPermissionPage() {
  const router = useRouter()
  const params = useParams<{ permissionId: string }>()
  const { permission, loading, notFound: permissionNotFound } = usePermission(
    params.permissionId
  )
  const updatePermission = usePermissionsStore((state) => state.updatePermission)

  if (permissionNotFound) {
    notFound()
  }

  async function handleSubmit(values: PermissionFormValues): Promise<string | null> {
    if (!permission) return "Permission not loaded yet."
    const { error } = await updatePermission(permission.id, values)
    if (error) return error
    router.push(`/admin/permissions/${permission.id}`)
    return null
  }

  return (
    <DashboardShell initialActiveLabel="Permissions">
      <AdminBreadcrumb
        className="mb-3"
        segments={[
          { label: "Dashboard", href: "/" },
          { label: "Permissions", href: "/admin/permissions" },
          {
            label: permission?.name ?? "Permission Details",
            href: permission ? `/admin/permissions/${permission.id}` : undefined,
          },
          { label: "Edit" },
        ]}
      />
      <div className="mb-[22px] max-w-[680px]">
          <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">
            Edit Permission
          </h1>
          <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
            {permission
              ? <>Update {permission.name}&apos;s name, description, module, and action.</>
              : "Update this permission's name, description, module, and action."}
          </p>
        </div>

        {loading ? (
          <div className="flex items-center justify-center gap-2 rounded-[14px] border border-border bg-card py-16 text-[13.5px] text-muted-foreground shadow-[var(--ledger-shadow)]">
            <Loader2 size={16} className="animate-spin" />
            Loading permission…
          </div>
        ) : permission ? (
          <PermissionForm mode="edit" permission={permission} onSubmit={handleSubmit} />
        ) : (
          <div className="flex flex-col items-center gap-2 rounded-[14px] border border-border bg-card py-16 text-center text-[13.5px] text-muted-foreground shadow-[var(--ledger-shadow)]">
            <AlertTriangle size={20} className="text-[var(--rust)]" />
            Couldn&apos;t load this permission.
          </div>
        )}
    </DashboardShell>
  )
}