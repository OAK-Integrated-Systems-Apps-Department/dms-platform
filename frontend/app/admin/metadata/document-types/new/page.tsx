"use client"

import { useRouter } from "next/navigation"
import { useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { CreateDocumentTypeForm } from "@/components/document-types/document-type-form"
import type { CreateDocumentTypeFormValues } from "@/components/document-types/schema"
import { createDocumentType } from "@/lib/document-types/api"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePageTitle } from "@/lib/use-page-title"

/** Add Document Type (A5). */
export default function AddDocumentTypePage() {
  usePageTitle("Add Document Type")
  const router = useRouter()
  const queryClient = useQueryClient()

  async function handleSubmit(values: CreateDocumentTypeFormValues): Promise<string | null> {
    try {
      const type = await createDocumentType({
        typeCode: values.typeCode,
        typeName: values.typeName,
        description: values.description || undefined,
        displayOrder: values.displayOrder,
        requiresWatermark: values.requiresWatermark,
        coeditingEnabled: values.coeditingEnabled,
      })
      queryClient.invalidateQueries({ queryKey: ["document-types"] })
      toast.success(`Document type "${type.typeName}" created.`)
      router.push(`/admin/metadata/document-types/${type.documentTypeId}`)
      return null
    } catch (err) {
      const message = err instanceof Error ? err.message : "Could not create this document type."
      toast.error(message)
      return message
    }
  }

  return (
    <DashboardShell initialActiveLabel="Metadata">
      <AdminBreadcrumb
        className="mb-3"
        segments={[{ label: "Dashboard", href: "/" }, { label: "Document Types", href: "/admin/metadata/document-types" }, { label: "Add Document Type" }]}
      />
      <div className="mb-[22px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">Add Document Type</h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Type code can&apos;t be changed after creation. Field mapping is added from the detail page&apos;s Fields tab once created.
        </p>
      </div>
      <PermissionGate permission={PermissionCodes.MDTM_CONTENT_TYPES_CREATE}>
        <CreateDocumentTypeForm onSubmit={handleSubmit} />
      </PermissionGate>
    </DashboardShell>
  )
}
