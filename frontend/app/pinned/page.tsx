"use client"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { PinnedDocumentsView } from "@/components/pins/pinned-documents-view"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePageTitle } from "@/lib/use-page-title"

/** Pinned documents (Module PID): the pins the signed-in user placed, and the ones placed for them. */
export default function PinnedDocumentsPage() {
  usePageTitle("Pinned Documents")
  return (
    <DashboardShell initialActiveLabel="Pinned Documents">
      <AdminBreadcrumb
        className="mb-3"
        segments={[
          { label: "Dashboard", href: "/" },
          { label: "Pinned Documents" },
        ]}
      />
      <div className="mb-5">
        <h1 className="mb-1.5 font-heading text-[26px] font-semibold tracking-[-0.01em]">
          Pinned documents
        </h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          The documents you pinned and the ones other people pinned for you.
          Pin one from My Documents, its preview or its own page, and choose who
          sees it. Only the person who pinned a document can unpin it.
        </p>
      </div>
      <PermissionGate permission={PermissionCodes.DOCM_DOCUMENTS_VIEW}>
        <PinnedDocumentsView />
      </PermissionGate>
    </DashboardShell>
  )
}
