"use client"

import { useState } from "react"
import Link from "next/link"
import { notFound, useParams, useRouter } from "next/navigation"
import { AlertTriangle, Loader2, Pencil, ShieldCheck, Trash2 } from "lucide-react"
import { toast } from "sonner"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { Badge } from "@/components/ui/badge"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { PermissionDeleteDialog } from "@/components/permissions/permission-delete-dialog"
import { usePermission } from "@/components/permissions/use-permission"
import { usePermissionsStore } from "@/stores/permissions-store"

/**
 * View Permission — read-only retrieval of a single permission's details.
 * Editing happens on Edit Permission; this screen is reference-only plus
 * the one destructive action (Delete), matching how Role Details handles
 * Delete Role alongside its own read-only view.
 */
export default function PermissionDetailsPage() {
  const router = useRouter()
  const params = useParams<{ permissionId: string }>()
  const { permission, loading, notFound: permissionNotFound } = usePermission(
    params.permissionId
  )
  const deletePermission = usePermissionsStore((state) => state.deletePermission)
  const [showDeleteDialog, setShowDeleteDialog] = useState(false)

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
      <div className="mb-[22px] max-w-[680px]">
          <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">
            Permission Details
          </h1>
          <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
            Definition of a single permission in the platform-wide catalog.
          </p>
        </div>

        {loading ? (
          <div className="flex items-center justify-center gap-2 rounded-[10px] border border-border bg-card py-16 text-[13.5px] text-muted-foreground shadow-[var(--ledger-shadow)]">
            <Loader2 size={16} className="animate-spin" />
            Loading permission…
          </div>
        ) : permission ? (
          <div className="rounded-[10px] border border-border bg-card px-[30px] py-[26px] shadow-[var(--ledger-shadow)]">
            <div className="mb-1 flex flex-wrap items-center gap-2.5">
              <h2 className="font-heading text-[20px] font-semibold">
                {permission.name}
              </h2>
              {permission.shared ? (
                <Badge variant="gold" className="gap-1">
                  <ShieldCheck size={11} />
                  Shared
                </Badge>
              ) : (
                <Badge variant="muted">Custom</Badge>
              )}
            </div>
            <p className="mb-6 font-mono text-[13px] text-muted-foreground">
              {permission.code}
            </p>

            {permission.shared && (
              <div className="mb-6 rounded-[9px] border border-[var(--gold)] bg-[var(--gold-tint)] px-3.5 py-2.5 text-[12.5px] text-[#7a5b00]">
                This is part of the platform&apos;s shared permission
                catalog. It&apos;s visible to every tenant and can&apos;t be
                edited or deleted from here.
              </div>
            )}

            <p className="mb-6 text-[13.5px] leading-[1.6] text-foreground">
              {permission.description || "No description provided."}
            </p>

            <hr className="my-6 border-dashed border-border" />

            <h3 className="mb-2.5 text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
              Module &amp; Action
            </h3>
            <div className="mb-6 flex flex-wrap gap-2">
              <Badge variant="outline" className="font-mono text-[12px]">
                Module: {permission.moduleCode}
              </Badge>
              <Badge variant="outline" className="font-mono text-[12px]">
                Action: {permission.actionCode}
              </Badge>
            </div>

            <hr className="my-6 border-dashed border-border" />

            {!permission.shared && (
              <div className="flex items-center justify-end gap-2.5">
                <button
                  type="button"
                  onClick={() => setShowDeleteDialog(true)}
                  className="flex items-center gap-1.5 rounded-[10px] border border-[var(--rust)] px-5 py-2.5 text-[13px] font-bold tracking-[0.02em] text-[var(--rust)]"
                >
                  <Trash2 size={14} />
                  Delete Permission
                </button>
                <Link
                  href={`/admin/permissions/${permission.id}/edit`}
                  className="flex items-center gap-1.5 rounded-[10px] border border-primary bg-primary px-5 py-2.5 text-[13px] font-bold tracking-[0.02em] text-primary-foreground"
                >
                  <Pencil size={14} />
                  Edit Permission
                </Link>
              </div>
            )}

            <PermissionDeleteDialog
              permission={permission}
              open={showDeleteDialog}
              onOpenChange={setShowDeleteDialog}
              onConfirm={async () => {
                const { error } = await deletePermission(permission.id)
                if (!error) {
                  toast.success(`${permission.name} deleted.`)
                  router.push("/admin/permissions")
                } else {
                  toast.error(error)
                }
                return error
              }}
            />
          </div>
        ) : (
          <div className="flex flex-col items-center gap-2 rounded-[10px] border border-border bg-card py-16 text-center text-[13.5px] text-muted-foreground shadow-[var(--ledger-shadow)]">
            <AlertTriangle size={20} className="text-[var(--rust)]" />
            Couldn&apos;t load this permission.
          </div>
        )}
    </DashboardShell>
  )
}