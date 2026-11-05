"use client"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { PreviewSettings } from "@/components/preview/preview-settings"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePageTitle } from "@/lib/use-page-title"

/**
 * Document preview and rendition management (DPRM): the organisation's preview security policy - what people may
 * print, download or copy from a preview - and the previews the server could not make, for review.
 */
export default function DocumentPreviewPage() {
  usePageTitle("Document Preview")
  return (
    <DashboardShell initialActiveLabel="Document Preview">
      <AdminBreadcrumb
        className="mb-3"
        segments={[{ label: "Dashboard", href: "/" }, { label: "Document Preview" }]}
      />
      <div className="mb-5">
        <h1 className="mb-1.5 font-heading text-[26px] font-semibold tracking-[-0.01em]">
          Document preview
        </h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Choose what people can do from a document preview, and review the
          files whose preview could not be made.
        </p>
      </div>
      <PermissionGate
        permission={[
          PermissionCodes.DPRM_RESTRICT_PRINT_DOWNLOAD,
          PermissionCodes.DPRM_TOGGLE_PREVIEW_WATERMARK,
        ]}
      >
        <PreviewSettings />
      </PermissionGate>
    </DashboardShell>
  )
}
