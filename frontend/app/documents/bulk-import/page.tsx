"use client"

import { PermissionGate } from "@/components/admin/permission-gate"
import { BulkImportWizard } from "@/components/bulk-import/bulk-import-wizard"
import { DocumentsHub } from "@/components/documents/documents-hub"
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
    <DashboardShell initialActiveLabel="My Documents">
      <DocumentsHub active="bulk-import">
        <PermissionGate
          permission={PermissionCodes.FIE_BULK_IMPORT_UPLOAD_FILE}
        >
          <div className="max-w-[1200px]">
            <BulkImportWizard />
          </div>
        </PermissionGate>
      </DocumentsHub>
    </DashboardShell>
  )
}
