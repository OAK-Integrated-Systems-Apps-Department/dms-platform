import { API_BASE_URL } from "@/lib/api-config"
import { apiFetchJson } from "@/lib/api-fetch"

/** Mirrors backend/dms-core's DocumentTypeFieldResponse DTO. */
export interface DocumentTypeFieldResponseDto {
  documentTypeFieldId: string
  documentTypeId: string
  fieldId: string
  fieldKey: string
  label: string
  schemaVersion: number
  status: "active" | "archived"
  mandatory: boolean
  hidden: boolean
  requiresBackfill: boolean
  displayOrder: number
  fieldGroup: string | null
  defaultValueOverride: string | null
  archivedAt: string | null
  /**
   * OCR label hints scoped to THIS document type for this field, in
   * priority order — tried before the field's own global hints
   * (`MetadataFieldResponseDto.labelAliases`, which are NOT repeated here).
   * Stored outside the versioned mapping, so it's the same on every
   * version's rows and is never part of the Publish payload below — see
   * `replaceDocumentTypeFieldLabelAliases`.
   */
  labelAliases: string[]
}

/**
 * Mirrors backend/dms-core's response for `GET`/`PUT .../fields/{fieldId}/label-aliases`.
 * (Only the PUT is called from here — the Fields tab already has every row's
 * type-scoped hints from `DocumentTypeFieldResponseDto.labelAliases`.)
 */
export interface DocumentTypeFieldLabelAliasesResponseDto {
  documentTypeId: string
  fieldId: string
  /** The type-scoped hints only — never the field's global ones. */
  labelAliases: string[]
}

export interface DocumentTypeFieldMappingItemPayload {
  fieldId: string
  mandatory: boolean
  hidden: boolean
  displayOrder: number
  fieldGroup?: string | null
  defaultValueOverride?: string | null
}

/** Mirrors backend/dms-core's DocumentTypeSchemaResponse DTO. */
export interface DocumentTypeSchemaResponseDto {
  documentTypeId: string
  typeCode: string
  typeName: string
  schemaVersion: number
  fields: {
    fieldId: string
    fieldKey: string
    label: string
    helpText: string | null
    dataType: string
    mandatory: boolean
    hidden: boolean
    ocrExtractable: boolean
    displayOrder: number
    fieldGroup: string | null
    defaultValue: string | null
    options: { optionId: string; optionValue: string; optionLabel: string }[]
    validations: { validationId: string; ruleType: string; ruleValue: string | null }[]
  }[]
}

function base(documentTypeId: string) {
  return `${API_BASE_URL}/api/v1/document-types/${documentTypeId}`
}

export async function fetchCurrentDocumentTypeFields(documentTypeId: string): Promise<DocumentTypeFieldResponseDto[]> {
  return apiFetchJson(`${base(documentTypeId)}/fields`, undefined, "Could not load this document type's fields.")
}

export async function fetchDocumentTypeFieldsAtVersion(
  documentTypeId: string,
  version: number
): Promise<DocumentTypeFieldResponseDto[]> {
  return apiFetchJson(
    `${base(documentTypeId)}/fields?version=${version}`,
    undefined,
    "Could not load this document type's fields."
  )
}

/**
 * Replaces a document type's whole field mapping in one call
 * (`PUT /{id}/fields`), which the backend turns into a new schema version —
 * unlike `syncRolePermissions`/`syncUserRoles`, there's no assign/revoke gap
 * to bridge client-side here, since A6 exposes a real bulk "set" endpoint.
 */
export async function replaceDocumentTypeFields(
  documentTypeId: string,
  fields: DocumentTypeFieldMappingItemPayload[]
): Promise<DocumentTypeFieldResponseDto[]> {
  return apiFetchJson(
    `${base(documentTypeId)}/fields`,
    { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ fields }) },
    "Could not save the field mapping."
  )
}

/**
 * Replaces one field's type-scoped OCR label hints wholesale (`[]` clears
 * them). Saved on its own, immediately — unlike the mapping, this does NOT
 * publish a new schema version, and the field must already be mapped into
 * the type's CURRENT published version (dms-core 404s otherwise, which is
 * why the Fields tab disables the editor for a row that's only been added
 * locally and not yet published).
 */
export async function replaceDocumentTypeFieldLabelAliases(
  documentTypeId: string,
  fieldId: string,
  labelAliases: string[]
): Promise<DocumentTypeFieldLabelAliasesResponseDto> {
  return apiFetchJson(
    `${base(documentTypeId)}/fields/${fieldId}/label-aliases`,
    { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ labelAliases }) },
    "Could not save this field's hints for this document type."
  )
}

export async function fetchDocumentTypeSchema(documentTypeId: string): Promise<DocumentTypeSchemaResponseDto> {
  return apiFetchJson(`${base(documentTypeId)}/schema`, undefined, "Could not load this document type's schema.")
}
