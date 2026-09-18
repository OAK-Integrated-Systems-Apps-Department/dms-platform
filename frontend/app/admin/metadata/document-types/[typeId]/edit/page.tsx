"use client"

import { notFound, useParams, useRouter } from "next/navigation"
import { AlertTriangle } from "lucide-react"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { LoadingState } from "@/components/ui/loading-state"
import { UpdateDocumentTypeForm } from "@/components/document-types/document-type-form"
import { useDocumentType } from "@/lib/document-types/use-document-type"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePageTitle } from "@/lib/use-page-title"

/** Edit Document Type (A5) — descriptive/behavioral attributes; field mapping is edited on the detail page's Fields tab. */
export default function EditDocumentTypePage() {
  const router = useRouter()
  const params = useParams<{ typeId: string }>()
  const { documentType, loading, notFound: typeNotFound, updateType } = useDocumentType(params.typeId)

  usePageTitle(documentType ? `Edit ${documentType.typeName}` : "Edit Document Type")

  if (typeNotFound) {
    notFound()
  }

  async function handleSubmit(values: Parameters<typeof updateType>[0]): Promise<string | null> {
    const error = await updateType(values)
    if (error) return error
    router.push(`/admin/metadata/document-types/${params.typeId}`)
    return null
  }

  return (
    <DashboardShell initialActiveLabel="Metadata">
      <AdminBreadcrumb
        className="mb-3"
        segments={[
          { label: "Dashboard", href: "/" },
          { label: "Document Types", href: "/admin/metadata/document-types" },
          { label: documentType?.typeName ?? "Document Type", href: documentType ? `/admin/metadata/document-types/${documentType.documentTypeId}` : undefined },
          { label: "Edit" },
        ]}
      />
      <div className="mb-[22px] max-w-[680px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">Edit Document Type</h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          {documentType ? <>Update {documentType.typeName}&apos;s attributes.</> : "Update this document type's attributes."}
        </p>
      </div>

      {loading ? (
        <LoadingState message="Loading document type…" />
      ) : documentType ? (
        <PermissionGate permission={PermissionCodes.MDTM_CONTENT_TYPES_UPDATE}>
          <UpdateDocumentTypeForm
            defaultValues={{
              typeName: documentType.typeName,
              description: documentType.description ?? "",
              displayOrder: documentType.displayOrder,
              requiresWatermark: documentType.requiresWatermark,
              coeditingEnabled: documentType.coeditingEnabled,
            }}
            onSubmit={handleSubmit}
          />
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
