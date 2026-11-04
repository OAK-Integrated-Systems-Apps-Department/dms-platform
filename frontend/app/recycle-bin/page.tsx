"use client"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { RecycleBin } from "@/components/documents/recycle-bin"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePageTitle } from "@/lib/use-page-title"

/** Deleted documents, restorable until the retention period ends (RBR). */
export default function RecycleBinPage() {
  usePageTitle("Recycle Bin")

  return (
    <DashboardShell initialActiveLabel="Recycle Bin">
      <AdminBreadcrumb
        className="mb-3"
        segments={[{ label: "Dashboard", href: "/" }, { label: "Recycle bin" }]}
      />
      <div className="mb-5">
        <h1 className="mb-1.5 font-heading text-[26px] font-semibold tracking-[-0.01em]">
          Recycle bin
        </h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Deleted documents wait here before they&apos;re deleted for good.
          Restore one to put it back where it was.
        </p>
      </div>
      <PermissionGate
        permission={[
          PermissionCodes.RBR_RESTORE,
          PermissionCodes.RBR_PURGE,
          PermissionCodes.RBR_RETENTION,
        ]}
      >
        <RecycleBin />
      </PermissionGate>
    </DashboardShell>
  )
}
