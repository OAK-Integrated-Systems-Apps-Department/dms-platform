"use client"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { DocumentUploadForm } from "@/components/documents/document-upload-form"
import { usePageTitle } from "@/lib/use-page-title"

/**
 * My Documents -- upload a file and run OCR on it, via dms-core's
 * passthrough route into backend/workers/ocr-worker
 * (com.oak.dms.document.DocumentController -> OcrWorkerClient). This is
 * the frontend half of that integration; ocr-worker's own README explains
 * the native-text-vs-PaddleOCR decision logic and output contract that
 * `DocumentUploadForm` renders.
 *
 * Top-level route (matches the sidebar's existing "My Documents" item --
 * see components/dashboard/nav-config.ts), not under /admin: any
 * authenticated user may upload/OCR within their own tenant, there is no
 * document-specific permission model yet.
 */
export default function DocumentsPage() {
  usePageTitle("My Documents")
  return (
    <DashboardShell>
      <AdminBreadcrumb className="mb-3" segments={[{ label: "Dashboard", href: "/" }, { label: "My Documents" }]} />
      <div className="mb-[22px] max-w-[680px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">My Documents</h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Upload a scanned document or image and run OCR on it.
        </p>
      </div>
      <div className="max-w-[680px]">
        <DocumentUploadForm />
      </div>
    </DashboardShell>
  )
}
