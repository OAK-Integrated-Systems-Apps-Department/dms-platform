"use client"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { BulkImportWizard } from "@/components/bulk-import/bulk-import-wizard"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePageTitle } from "@/lib/use-page-title"

/**
 * Bulk Import (Module 2.1, upload intake): download a template, upload it
 * filled in, upload the files it lists, then submit - or resume/cancel an
 * import already in progress. Every step is gated server-side on
 * fie.bulk-import-documents.upload-file.
 */
export default function BulkImportPage() {
  usePageTitle("Bulk Import")

  return (
    <DashboardShell initialActiveLabel="Bulk Import">
      <AdminBreadcrumb className="mb-3" segments={[{ label: "Dashboard", href: "/" }, { label: "Bulk Import" }]} />
      <div className="mb-[22px] max-w-[680px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">Bulk Import</h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Import many documents at once: fill in a template with one row per file, upload the files it lists, then
          submit the batch for processing.
        </p>
      </div>
      <PermissionGate permission={PermissionCodes.FIE_BULK_IMPORT_UPLOAD_FILE}>
        <div className="max-w-[960px]">
          <BulkImportWizard />
        </div>
      </PermissionGate>
    </DashboardShell>
  )
}
