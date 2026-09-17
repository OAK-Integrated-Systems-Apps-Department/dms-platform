"use client"

import { useRouter } from "next/navigation"
import { toast } from "sonner"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { CreateMetadataFieldForm } from "@/components/metadata-fields/metadata-field-form"
import type { CreateMetadataFieldFormValues } from "@/components/metadata-fields/schema"
import { createMetadataField } from "@/lib/metadata-fields/api"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePageTitle } from "@/lib/use-page-title"
import { useQueryClient } from "@tanstack/react-query"

/** Add Field (A3) — adds a new entry to the tenant's metadata schema registry. */
export default function AddMetadataFieldPage() {
  usePageTitle("Add Metadata Field")
  const router = useRouter()
  const queryClient = useQueryClient()

  async function handleSubmit(values: CreateMetadataFieldFormValues): Promise<string | null> {
    try {
      const field = await createMetadataField({
        fieldKey: values.fieldKey,
        label: values.label,
        helpText: values.helpText || undefined,
        dataType: values.dataType,
        defaultValue: values.defaultValue || undefined,
        systemField: values.systemField,
        searchable: values.searchable,
        ocrExtractable: values.ocrExtractable,
      })
      queryClient.invalidateQueries({ queryKey: ["metadata-fields"] })
      toast.success(`Field "${field.label}" created.`)
      router.push("/admin/metadata/fields")
      return null
    } catch (err) {
      const message = err instanceof Error ? err.message : "Could not create this field."
      toast.error(message)
      return message
    }
  }

  return (
    <DashboardShell initialActiveLabel="Metadata">
      <AdminBreadcrumb
        className="mb-3"
        segments={[{ label: "Dashboard", href: "/" }, { label: "Metadata Fields", href: "/admin/metadata/fields" }, { label: "Add Field" }]}
      />
      <div className="mb-[22px] max-w-[680px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">Add Metadata Field</h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Field key and data type can&apos;t be changed after creation — choose carefully.
        </p>
      </div>
      <PermissionGate permission={PermissionCodes.MDTM_METADATA_SCHEMA_CREATE_FIELD}>
        <CreateMetadataFieldForm onSubmit={handleSubmit} />
      </PermissionGate>
    </DashboardShell>
  )
}
