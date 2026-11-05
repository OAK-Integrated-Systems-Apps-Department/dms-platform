"use client"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { ArchiveView } from "@/components/retention/archive-view"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePageTitle } from "@/lib/use-page-title"

/** Inactive & Archive (6.1, CRM-FR-005): the documents hidden from the normal listings, for people allowed to see them. */
export default function ArchivePage() {
  usePageTitle("Inactive & Archive")
  return (
    <DashboardShell initialActiveLabel="Inactive & Archive" fillHeight>
      <AdminBreadcrumb
        className="mb-3"
        segments={[{ label: "Dashboard", href: "/" }, { label: "My Documents", href: "/documents" }, { label: "Inactive & Archive" }]}
      />
      <div className="mb-5">
        <h1 className="mb-1.5 font-heading text-[26px] font-semibold tracking-[-0.01em]">Inactive &amp; Archive</h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Documents switched off by hand or archived by a retention rule. They are hidden from normal listings and search
          but remain fully accessible here.
        </p>
      </div>
      <PermissionGate permission={PermissionCodes.CRM_VIEW_ARCHIVE}>
        <ArchiveView />
      </PermissionGate>
    </DashboardShell>
  )
}
