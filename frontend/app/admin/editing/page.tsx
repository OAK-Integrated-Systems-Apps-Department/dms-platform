"use client"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { EditingSettingsCard } from "@/components/checkout/editing-settings-card"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { usePageTitle } from "@/lib/use-page-title"

/**
 * Online editing (2.10, CICO-FR-010): the organisation's own switch and mode. Visible to anyone holding either
 * cico.configure-editing-engine permission; changing it needs select-provider.
 */
export default function OnlineEditingSettingsPage() {
  usePageTitle("Online Editing")
  const { hasPermission } = usePermissions()
  return (
    <DashboardShell initialActiveLabel="Online Editing">
      <AdminBreadcrumb
        className="mb-3"
        segments={[{ label: "Dashboard", href: "/" }, { label: "Online Editing" }]}
      />
      <div className="mb-5 max-w-[760px]">
        <h1 className="mb-1.5 font-heading text-[26px] font-semibold tracking-[-0.01em]">Online editing</h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Choose whether people in your organisation can edit documents in the browser, and how they share a document
          while they do.
        </p>
      </div>
      <PermissionGate
        permission={[
          PermissionCodes.CICO_EDITING_SELECT_PROVIDER,
          PermissionCodes.CICO_EDITING_CONFIGURE_CONNECTION,
        ]}
      >
        <EditingSettingsCard canEdit={hasPermission(PermissionCodes.CICO_EDITING_SELECT_PROVIDER)} />
      </PermissionGate>
    </DashboardShell>
  )
}
