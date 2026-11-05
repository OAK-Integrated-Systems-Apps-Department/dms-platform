"use client"

import { useState } from "react"
import { notFound, useParams } from "next/navigation"
import Link from "next/link"
import { AlertTriangle, Pencil } from "lucide-react"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { Badge } from "@/components/ui/badge"
import { LoadingState } from "@/components/ui/loading-state"
import { cn } from "@/lib/utils"

import { DocumentTypeFieldsTab } from "@/components/document-types/document-type-fields-tab"
import { DocumentTypeTemplatesTab } from "@/components/document-types/document-type-templates-tab"
import { useDocumentType } from "@/lib/document-types/use-document-type"
import { useMetadataFields } from "@/lib/metadata-fields/use-metadata-fields"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { usePageTitle } from "@/lib/use-page-title"

type Tab = "details" | "fields" | "templates"

/**
 * Document Type Details (A5) with a Fields tab for editing this type's versioned field mapping (A6),
 * and a Templates tab for the ready-made files people start this type's documents from.
 */
export default function DocumentTypeDetailPage() {
  const params = useParams<{ typeId: string }>()
  const { hasPermission, hasAnyPermission } = usePermissions()
  const canViewMapping = hasAnyPermission(PermissionCodes.MDTM_TYPE_FIELD_MAPPING_VIEW, PermissionCodes.MDTM_TYPE_FIELD_MAPPING_UPDATE)
  const { documentType, loading, notFound: typeNotFound, fields, replaceFields } = useDocumentType(params.typeId, {
    withFields: canViewMapping,
  })
  const canView = hasPermission(PermissionCodes.MDTM_METADATA_SCHEMA_VIEW_FIELD) || hasPermission(PermissionCodes.MDTM_METADATA_SCHEMA_UPDATE_FIELD)
  const { fields: catalogueFields } = useMetadataFields(canView)
  const canUpdate = hasPermission(PermissionCodes.MDTM_CONTENT_TYPES_UPDATE)
  const canUpdateMapping = hasPermission(PermissionCodes.MDTM_TYPE_FIELD_MAPPING_UPDATE)
  // Templates have their own permissions (V70) - each button shows only with its own.
  const templateAccess = {
    canCreate: hasPermission(PermissionCodes.MDTM_DOCUMENT_TEMPLATES_CREATE),
    canUpdate: hasPermission(PermissionCodes.MDTM_DOCUMENT_TEMPLATES_UPDATE),
    canDelete: hasPermission(PermissionCodes.MDTM_DOCUMENT_TEMPLATES_DELETE),
  }
  const tabs: Tab[] = canViewMapping ? ["details", "fields", "templates"] : ["details", "templates"]

  const [tab, setTab] = useState<Tab>("details")

  usePageTitle(documentType ? documentType.typeName : "Document Type Details")

  if (typeNotFound) {
    notFound()
  }

  return (
    <DashboardShell initialActiveLabel="Metadata">
      <AdminBreadcrumb
        className="mb-3"
        segments={[
          { label: "Dashboard", href: "/" },
          { label: "Document Types", href: "/admin/metadata/document-types" },
          { label: documentType?.typeName ?? "Document Type" },
        ]}
      />

      {loading ? (
        <LoadingState message="Loading document type…" />
      ) : documentType ? (
        <PermissionGate
          permission={[
            PermissionCodes.MDTM_CONTENT_TYPES_VIEW,
            PermissionCodes.MDTM_CONTENT_TYPES_UPDATE,
            PermissionCodes.MDTM_DOCUMENT_TEMPLATES_CREATE,
            PermissionCodes.MDTM_DOCUMENT_TEMPLATES_UPDATE,
            PermissionCodes.MDTM_DOCUMENT_TEMPLATES_DELETE,
          ]}
        >
          <div className="mb-[22px] flex items-start justify-between gap-4">
            <div>
              <div className="mb-2 flex items-center gap-2.5">
                <h1 className="font-heading text-[26px] font-semibold tracking-[-0.01em]">{documentType.typeName}</h1>
                <Badge variant={documentType.status === "active" ? "default" : "muted"} className="rounded-btn">{documentType.status}</Badge>
              </div>
              <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
                {documentType.description || "No description."} Current schema version v{documentType.currentSchemaVersion}.
              </p>
            </div>
            {canUpdate && (
              <Link
                href={`/admin/metadata/document-types/${documentType.documentTypeId}/edit`}
                className="flex shrink-0 items-center gap-1.5 border border-[var(--border-strong)] bg-card font-semibold text-foreground h-[38px] rounded-btn px-4 text-[13px] justify-center"
              >
                <Pencil size={15} />
                Edit
              </Link>
            )}
          </div>

          <div className="mb-5 flex items-center gap-1 border-b border-border">
            {tabs.map((t) => (
              <button
                key={t}
                type="button"
                onClick={() => setTab(t)}
                className={cn(
                  "border-b-2 px-4 py-2.5 text-[13px] font-semibold capitalize transition-colors",
                  tab === t ? "border-primary text-primary" : "border-transparent text-muted-foreground hover:text-foreground"
                )}
              >
                {t === "details" ? "Details" : t === "fields" ? "Fields" : "Templates"}
              </button>
            ))}
          </div>

          {tab === "details" ? (
            <div className="grid grid-cols-2 gap-x-6 gap-y-4 rounded-lg border border-border bg-card px-[30px] py-[26px] shadow-[var(--ledger-shadow)]">
              <DetailRow label="Type Code" value={documentType.typeCode} mono />
              <DetailRow label="Display Order" value={String(documentType.displayOrder)} />
              <DetailRow label="Requires Watermark" value={documentType.requiresWatermark ? "Yes" : "No"} />
              <DetailRow label="Co-editing Enabled" value={documentType.coeditingEnabled ? "Yes" : "No"} />
              <DetailRow label="Schema Version" value={`v${documentType.currentSchemaVersion}`} />
            </div>
          ) : tab === "templates" ? (
            <DocumentTypeTemplatesTab documentTypeId={documentType.documentTypeId} {...templateAccess} />
          ) : (
            <DocumentTypeFieldsTab
              currentFields={fields}
              availableFields={catalogueFields}
              canUpdate={canUpdateMapping}
              onPublish={(rows) =>
                replaceFields(
                  rows.map((row, index) => ({
                    fieldId: row.fieldId,
                    mandatory: row.mandatory,
                    hidden: row.hidden,
                    displayOrder: (index + 1) * 10,
                    fieldGroup: row.fieldGroup || null,
                    defaultValueOverride: row.defaultValueOverride || null,
                  }))
                )
              }
            />
          )}
        </PermissionGate>
      ) : (
        <div className="flex flex-col items-center gap-2 rounded-lg border border-border bg-card py-16 text-center text-[13.5px] text-muted-foreground shadow-[var(--ledger-shadow)]">
          <AlertTriangle size={20} className="text-[var(--rust)]" />
          Couldn&apos;t load this document type.
        </div>
      )}
    </DashboardShell>
  )
}

function DetailRow({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div>
      <p className="mb-1 text-[11px] font-semibold tracking-[0.07em] text-muted-foreground uppercase">{label}</p>
      <p className={cn("text-[13.5px] text-foreground", mono && "font-mono text-[12.5px]")}>{value}</p>
    </div>
  )
}
