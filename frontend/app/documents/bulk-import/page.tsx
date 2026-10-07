"use client"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { BulkImportWizard } from "@/components/bulk-import/bulk-import-wizard"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { usePageTitle } from "@/lib/use-page-title"

/**
 * Bulk Import (Module 2.1, upload intake): choose a document type, add the
 * files, then submit - each file becomes a document with the type's default
 * values - or resume/cancel an import already in progress. Every step is gated server-side on
 * fie.bulk-import-documents.upload-file; starting, uploading to and
 * submitting an import also need docm.documents.create, since the import
 * creates documents. Without it the wizard explains why instead.
 */
export default function BulkImportPage() {
  usePageTitle("Bulk Import")
  const { hasPermission } = usePermissions()

  return (
    <DashboardShell initialActiveLabel="Bulk Import">
      <AdminBreadcrumb className="mb-3" segments={[{ label: "Dashboard", href: "/" }, { label: "Bulk Import" }]} />
      <div className="mb-[22px] max-w-[680px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">Bulk Import</h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Import many documents at once: choose a document type, add the files, then submit them for processing.
          Each file becomes a document with the type&apos;s default values.
        </p>
      </div>
      <PermissionGate permission={PermissionCodes.FIE_BULK_IMPORT_UPLOAD_FILE}>
        <div className="max-w-[960px]">
          <BulkImportWizard canCreateDocuments={hasPermission(PermissionCodes.DOCM_DOCUMENTS_CREATE)} />
        </div>
      </PermissionGate>
    </DashboardShell>
  )
}
