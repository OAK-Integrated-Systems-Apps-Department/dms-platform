"use client"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { DocumentUploadForm } from "@/components/documents/document-upload-form"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePageTitle } from "@/lib/use-page-title"

/**
 * Scan Documents - upload a file, pick which fields OCR should extract, and
 * watch it come back.
 *
 * Its own route rather than a tab inside My Documents: the file picker, the
 * field picker and the per-field review that follows are one task with one
 * shape, and folding it into the browser would mean two different things
 * happening on one screen.
 *
 * The upload itself is the ordinary one - same endpoint, same malware scan
 * gate, same folder rules. The only difference is that it carries a field
 * selection, and OCR starts once the file comes back clean. A document
 * scanned here appears in My Documents like any other.
 */
export default function ScanDocumentsPage() {
  usePageTitle("Scan Documents")

  return (
    <DashboardShell initialActiveLabel="Scan documents">
      <AdminBreadcrumb
        className="mb-3"
        segments={[
          { label: "Dashboard", href: "/" },
          { label: "My Documents", href: "/documents" },
          { label: "Scan documents" },
        ]}
      />
      <div className="mb-[22px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">
          Scan documents
        </h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Upload a scanned document or image and choose which fields to read from it. The file is
          checked for malware first; OCR runs once it comes back clean.
        </p>
      </div>
      <PermissionGate permission={PermissionCodes.OS_OCR_TRIGGER}>
        <DocumentUploadForm />
      </PermissionGate>
    </DashboardShell>
  )
}
