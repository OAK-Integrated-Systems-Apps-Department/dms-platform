"use client"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { SharedWithMe } from "@/components/documents/shared-with-me"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePageTitle } from "@/lib/use-page-title"

/**
 * Shared with me (V53/V54): folders and documents someone gave the caller
 * access to - including ones inside folders they can't otherwise open, which
 * My Documents' folder-by-folder browsing would never reach.
 */
export default function SharedWithMePage() {
  usePageTitle("Shared With Me")

  return (
    <DashboardShell initialActiveLabel="Shared With Me">
      <AdminBreadcrumb
        className="mb-3"
        segments={[
          { label: "Dashboard", href: "/" },
          { label: "Shared with me" },
        ]}
      />
      <div className="mb-5 max-w-[720px]">
        <h1 className="mb-1.5 font-heading text-[26px] font-semibold tracking-[-0.01em]">
          Shared with me
        </h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Folders and documents other people gave you access to, by name or
          through one of your roles - wherever they&apos;re filed.
        </p>
      </div>
      <PermissionGate permission={PermissionCodes.DOCM_DOCUMENTS_VIEW}>
        <SharedWithMe />
      </PermissionGate>
    </DashboardShell>
  )
}
