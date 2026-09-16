"use client"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { DocumentTypeFieldMatrix } from "@/components/document-type-mapping/document-type-field-matrix"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { usePageTitle } from "@/lib/use-page-title"

/** Type Field Mapping (A6) — document-type x field matrix for comparing and bulk-editing mappings across types. */
export default function TypeFieldMappingPage() {
  usePageTitle("Type Field Mapping")
  const { hasPermission } = usePermissions()
  const canUpdate = hasPermission(PermissionCodes.MDTM_TYPE_FIELD_MAPPING_UPDATE)

  return (
    <DashboardShell initialActiveLabel="Metadata">
      <AdminBreadcrumb className="mb-3" segments={[{ label: "Dashboard", href: "/" }, { label: "Type Field Mapping" }]} />
      <div className="mb-[22px] max-w-[680px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">Type Field Mapping</h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Compare which fields each document type uses, and bulk-edit mandatory/optional across types. Click a cell
          to cycle not included → optional → mandatory. Saving publishes a new schema version for every changed
          document type.
        </p>
      </div>
      <PermissionGate permission={[PermissionCodes.MDTM_TYPE_FIELD_MAPPING_VIEW, PermissionCodes.MDTM_TYPE_FIELD_MAPPING_UPDATE]}>
        <DocumentTypeFieldMatrix canUpdate={canUpdate} />
      </PermissionGate>
    </DashboardShell>
  )
}
