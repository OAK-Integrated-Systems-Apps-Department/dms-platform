import { API_BASE_URL } from "@/lib/api-config"
import { apiFetch, apiFetchJson } from "@/lib/api-fetch"

/** Mirrors backend/dms-core's MetadataFieldResponse DTO. */
export interface MetadataFieldResponseDto {
  fieldId: string
  tenantId: string
  fieldKey: string
  label: string
  helpText: string | null
  dataType: string
  defaultValue: string | null
  systemField: boolean
  searchable: boolean
  indexed: boolean
  ocrExtractable: boolean
  status: "active" | "inactive" | "deprecated"
  createdAt: string
  updatedAt: string | null
}

/** Mirrors backend/dms-core's PageResponse<MetadataFieldResponse> envelope. */
export interface MetadataFieldPageResponseDto {
  content: MetadataFieldResponseDto[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  last: boolean
}

export interface CreateMetadataFieldPayload {
  fieldKey: string
  label: string
  helpText?: string
  dataType: string
  defaultValue?: string
  systemField: boolean
  searchable: boolean
  indexed: boolean
  ocrExtractable: boolean
}

export interface UpdateMetadataFieldPayload {
  label: string
  helpText?: string
  defaultValue?: string
  searchable: boolean
  indexed: boolean
  ocrExtractable: boolean
}

/** Mirrors backend/dms-core's MetadataFieldOptionResponse DTO. */
export interface MetadataFieldOptionResponseDto {
  optionId: string
  fieldId: string
  parentOptionId: string | null
  optionValue: string
  optionLabel: string
  displayOrder: number
  status: "active" | "inactive"
}

export interface CreateMetadataFieldOptionPayload {
  optionValue: string
  optionLabel: string
  parentOptionId?: string | null
  displayOrder: number
}

export interface UpdateMetadataFieldOptionPayload extends CreateMetadataFieldOptionPayload {
  status: "active" | "inactive"
}

export const VALIDATION_RULE_TYPES = [
  "min_length", "max_length", "min_value", "max_value", "regex", "date_format", "date_range", "allowed_values",
] as const
export type ValidationRuleType = (typeof VALIDATION_RULE_TYPES)[number]

/** Mirrors backend/dms-core's MetadataFieldValidationResponse DTO. */
export interface MetadataFieldValidationResponseDto {
  validationId: string
  fieldId: string
  ruleType: ValidationRuleType
  ruleValue: string | null
  errorMessage: string | null
  severity: "error" | "warning"
}

export interface CreateMetadataFieldValidationPayload {
  ruleType: ValidationRuleType
  ruleValue?: string
  errorMessage?: string
  severity?: "error" | "warning"
}

export interface UpdateMetadataFieldValidationPayload {
  ruleValue?: string
  errorMessage?: string
  severity?: "error" | "warning"
}

const BASE = `${API_BASE_URL}/api/v1/metadata-fields`

// GET /api/v1/metadata-fields has server-side status/dataType/search filters
// and real Pageable paging, but this fetches everything in large pages (same
// PAGE_FETCH_SIZE loop as lib/users/api.ts's fetchAllUsers) so the Fields
// list screen can filter/sort/paginate client-side — consistent with how
// the Users and Roles lists already work, rather than introducing a second,
// server-driven pattern for one screen.
const PAGE_FETCH_SIZE = 200

export async function fetchAllMetadataFields(): Promise<MetadataFieldResponseDto[]> {
  const fields: MetadataFieldResponseDto[] = []
  let page = 0
  while (true) {
    const response = await apiFetchJson<MetadataFieldPageResponseDto>(
      `${BASE}?page=${page}&size=${PAGE_FETCH_SIZE}`,
      undefined,
      "Could not load metadata fields."
    )
    fields.push(...response.content)
    if (response.last) break
    page += 1
  }
  return fields
}

export async function fetchMetadataField(fieldId: string): Promise<MetadataFieldResponseDto> {
  return apiFetchJson(`${BASE}/${fieldId}`, undefined, "Could not load this metadata field.")
}

export async function createMetadataField(payload: CreateMetadataFieldPayload): Promise<MetadataFieldResponseDto> {
  return apiFetchJson(
    BASE,
    { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) },
    "Could not create this metadata field."
  )
}

export async function updateMetadataField(
  fieldId: string,
  payload: UpdateMetadataFieldPayload
): Promise<MetadataFieldResponseDto> {
  return apiFetchJson(
    `${BASE}/${fieldId}`,
    { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) },
    "Could not save changes."
  )
}

export async function activateMetadataField(fieldId: string): Promise<MetadataFieldResponseDto> {
  return apiFetchJson(`${BASE}/${fieldId}/activate`, { method: "POST" }, "Could not activate this field.")
}

export async function deactivateMetadataField(fieldId: string): Promise<MetadataFieldResponseDto> {
  return apiFetchJson(`${BASE}/${fieldId}/deactivate`, { method: "POST" }, "Could not deactivate this field.")
}

export async function deprecateMetadataField(fieldId: string): Promise<MetadataFieldResponseDto> {
  return apiFetchJson(`${BASE}/${fieldId}/deprecate`, { method: "POST" }, "Could not deprecate this field.")
}

export async function deleteMetadataField(fieldId: string): Promise<void> {
  await apiFetch(`${BASE}/${fieldId}`, { method: "DELETE" }, "Could not delete this field.")
}

export async function fetchFieldOptions(fieldId: string): Promise<MetadataFieldOptionResponseDto[]> {
  return apiFetchJson(`${BASE}/${fieldId}/options`, undefined, "Could not load this field's options.")
}

export async function createFieldOption(
  fieldId: string,
  payload: CreateMetadataFieldOptionPayload
): Promise<MetadataFieldOptionResponseDto> {
  return apiFetchJson(
    `${BASE}/${fieldId}/options`,
    { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) },
    "Could not add this option."
  )
}

export async function updateFieldOption(
  fieldId: string,
  optionId: string,
  payload: UpdateMetadataFieldOptionPayload
): Promise<MetadataFieldOptionResponseDto> {
  return apiFetchJson(
    `${BASE}/${fieldId}/options/${optionId}`,
    { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) },
    "Could not update this option."
  )
}

export async function deleteFieldOption(fieldId: string, optionId: string): Promise<void> {
  await apiFetch(`${BASE}/${fieldId}/options/${optionId}`, { method: "DELETE" }, "Could not delete this option.")
}

export async function fetchFieldValidations(fieldId: string): Promise<MetadataFieldValidationResponseDto[]> {
  return apiFetchJson(`${BASE}/${fieldId}/validations`, undefined, "Could not load this field's validation rules.")
}

export async function createFieldValidation(
  fieldId: string,
  payload: CreateMetadataFieldValidationPayload
): Promise<MetadataFieldValidationResponseDto> {
  return apiFetchJson(
    `${BASE}/${fieldId}/validations`,
    { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) },
    "Could not add this validation rule."
  )
}

export async function updateFieldValidation(
  fieldId: string,
  validationId: string,
  payload: UpdateMetadataFieldValidationPayload
): Promise<MetadataFieldValidationResponseDto> {
  return apiFetchJson(
    `${BASE}/${fieldId}/validations/${validationId}`,
    { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) },
    "Could not update this validation rule."
  )
}

export async function deleteFieldValidation(fieldId: string, validationId: string): Promise<void> {
  await apiFetch(
    `${BASE}/${fieldId}/validations/${validationId}`,
    { method: "DELETE" },
    "Could not delete this validation rule."
  )
}
