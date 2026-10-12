"use client"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { BulkExportPageContent } from "@/components/bulk-export/bulk-export-page-content"
import { InlineError } from "@/components/bulk-import/step-card"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { usePageTitle } from "@/lib/use-page-title"

/**
 * Bulk Export (Module 2.1): filter and pick documents, then request
 * a ZIP of their files plus a metadata sheet, built in the background and
 * downloadable from My exports until it expires. Every endpoint needs both
 * fie.bulk-export-documents.execute-export and docm.documents.view; the
 * sidebar entry only checks the first, so the page explains the second.
 */
export default function BulkExportPage() {
  usePageTitle("Bulk Export")
  const { hasPermission } = usePermissions()

  return (
    <DashboardShell initialActiveLabel="Bulk Export">
      <AdminBreadcrumb className="mb-3" segments={[{ label: "Dashboard", href: "/" }, { label: "Bulk Export" }]} />
      <div className="mb-[22px] max-w-[680px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">Bulk Export</h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Export documents in bulk: filter and select documents, then download a ZIP of their files with a
          metadata sheet in Excel or CSV.
        </p>
      </div>
      <PermissionGate permission={PermissionCodes.FIE_BULK_EXPORT_EXECUTE}>
        <div className="max-w-[1100px]">
          {hasPermission(PermissionCodes.DOCM_DOCUMENTS_VIEW) ? (
            <BulkExportPageContent />
          ) : (
            <InlineError>
              You also need permission to view documents to export them. Ask your administrator.
            </InlineError>
          )}
        </div>
      </PermissionGate>
    </DashboardShell>
  )
}
