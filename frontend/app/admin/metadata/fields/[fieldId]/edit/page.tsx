"use client"

import { notFound, useParams } from "next/navigation"
import { AlertTriangle } from "lucide-react"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { LoadingState } from "@/components/ui/loading-state"
import { UpdateMetadataFieldForm } from "@/components/metadata-fields/metadata-field-form"
import { FieldOptionEditor } from "@/components/metadata-fields/field-option-editor"
import { FieldValidationEditor } from "@/components/metadata-fields/field-validation-editor"
import { OPTION_BACKED_DATA_TYPES, type DataType } from "@/components/metadata-fields/types"
import { useMetadataField } from "@/lib/metadata-fields/use-metadata-field"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePageTitle } from "@/lib/use-page-title"

/** Edit Field (A3/A4) — update a field's attributes plus its option list and validation rules. */
export default function EditMetadataFieldPage() {
  const params = useParams<{ fieldId: string }>()
  const {
    field, loading, notFound: fieldNotFound,
    options, validations,
    updateField, addOption, updateOption, removeOption, addValidation, updateValidation, removeValidation,
  } = useMetadataField(params.fieldId)

  usePageTitle(field ? `Edit ${field.label}` : "Edit Metadata Field")

  if (fieldNotFound) {
    notFound()
  }

  const isOptionBacked = field ? OPTION_BACKED_DATA_TYPES.includes(field.dataType as DataType) : false

  return (
    <DashboardShell initialActiveLabel="Metadata">
      <AdminBreadcrumb
        className="mb-3"
        segments={[
          { label: "Dashboard", href: "/" },
          { label: "Metadata Fields", href: "/admin/metadata/fields" },
          { label: field?.label ?? "Edit Field" },
        ]}
      />
      <div className="mb-[22px] max-w-[680px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">Edit Metadata Field</h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          {field ? <>Update {field.label}&apos;s attributes, options, and validation rules.</> : "Update this field's attributes, options, and validation rules."}
        </p>
      </div>

      {loading ? (
        <LoadingState message="Loading field…" />
      ) : field ? (
        <PermissionGate permission={[PermissionCodes.MDTM_METADATA_SCHEMA_UPDATE_FIELD, PermissionCodes.MDTM_METADATA_SCHEMA_SET_VALIDATION_RULES]}>
          <div className="flex flex-col gap-6">
            <UpdateMetadataFieldForm
              defaultValues={{
                label: field.label,
                helpText: field.helpText ?? "",
                defaultValue: field.defaultValue ?? "",
                searchable: field.searchable,
                indexed: field.indexed,
                ocrExtractable: field.ocrExtractable,
              }}
              onSubmit={updateField}
            />

            {isOptionBacked && (
              <section className="rounded-lg border border-border bg-card px-[30px] py-[26px] shadow-[var(--ledger-shadow)]">
                <h2 className="mb-4 font-heading text-[16px] font-semibold">Options</h2>
                <FieldOptionEditor fieldId={field.fieldId} options={options} onAdd={addOption} onUpdate={updateOption} onRemove={removeOption} />
              </section>
            )}

            <section className="rounded-lg border border-border bg-card px-[30px] py-[26px] shadow-[var(--ledger-shadow)]">
              <h2 className="mb-4 font-heading text-[16px] font-semibold">Validation Rules</h2>
              <FieldValidationEditor
                validations={validations}
                onAdd={(payload) => addValidation(payload)}
                onUpdate={(id, payload) => updateValidation(id, payload)}
                onRemove={removeValidation}
              />
            </section>
          </div>
        </PermissionGate>
      ) : (
        <div className="flex flex-col items-center gap-2 rounded-lg border border-border bg-card py-16 text-center text-[13.5px] text-muted-foreground shadow-[var(--ledger-shadow)]">
          <AlertTriangle size={20} className="text-[var(--rust)]" />
          Couldn&apos;t load this field.
        </div>
      )}
    </DashboardShell>
  )
}
