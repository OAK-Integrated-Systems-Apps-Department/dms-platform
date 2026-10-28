"use client"

import { Suspense } from "react"

import { PermissionGate } from "@/components/admin/permission-gate"
import { DocumentBrowser } from "@/components/documents/document-browser"
import { DocumentsHub } from "@/components/documents/documents-hub"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePageTitle } from "@/lib/use-page-title"

/**
 * My Documents (Module DSC): the organisation's shared folders and the
 * documents in them. Browsing needs docm.documents.view; each action needs
 * its own permission (checked server-side too).
 */
export default function DocumentsPage() {
  usePageTitle("My Documents")

  return (
    <DashboardShell initialActiveLabel="My Documents">
      <DocumentsHub active="documents">
        <PermissionGate permission={PermissionCodes.DOCM_DOCUMENTS_VIEW}>
          <Suspense fallback={null}>
            <DocumentBrowser />
          </Suspense>
        </PermissionGate>
      </DocumentsHub>
    </DashboardShell>
  )
}
