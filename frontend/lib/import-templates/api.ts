import { API_BASE_URL } from "@/lib/api-config"
import { apiFetch, apiFetchJson } from "@/lib/api-fetch"
import type { DownloadedFile, TemplateFormat } from "@/lib/bulk-imports/api"

const BASE = `${API_BASE_URL}/api/v1/import-templates`
const JSON_HEADERS = { "Content-Type": "application/json" }

/** One metadata column of a saved template. `header` null/blank = the field's own label. */
export interface ImportTemplateColumnDto {
  fieldKey: string
  header: string | null
}

/** Mirrors ImportTemplateResponse. */
export interface ImportTemplateDto {
  importTemplateId: string
  documentTypeId: string
  documentTypeName: string | null
  name: string
  description: string | null
  columns: ImportTemplateColumnDto[]
  /** field_key -> default value. */
  defaults: Record<string, string>
  /** Columns whose field is no longer on the form - left out of downloads. */
  missingFields: string[]
  createdAt: string
  updatedAt: string | null
}

/** Mirrors ImportTemplateRequest. */
export interface ImportTemplateRequestDto {
  name: string
  description: string | null
  documentTypeId?: string
  columns: ImportTemplateColumnDto[]
  defaults: Record<string, string>
}

/** Mirrors ImportTemplateFieldResponse - a field the editor can offer. */
export interface ImportTemplateFieldDto {
  fieldKey: string
  label: string
  dataType: string
  mandatory: boolean
  helpText: string | null
  options: string[]
}

export async function fetchImportTemplates(documentTypeId?: string): Promise<ImportTemplateDto[]> {
  const query = documentTypeId ? `?documentTypeId=${encodeURIComponent(documentTypeId)}` : ""
  return apiFetchJson(`${BASE}/saved${query}`, undefined, "Could not load the saved templates.")
}

export async function fetchImportTemplateFields(documentTypeId: string): Promise<ImportTemplateFieldDto[]> {
  return apiFetchJson(
    `${BASE}/fields?documentTypeId=${encodeURIComponent(documentTypeId)}`,
    undefined,
    "Could not load the document type's fields."
  )
}

export async function createImportTemplate(request: ImportTemplateRequestDto): Promise<ImportTemplateDto> {
  return apiFetchJson(
    `${BASE}/saved`,
    { method: "POST", headers: JSON_HEADERS, body: JSON.stringify(request) },
    "Could not save the template."
  )
}

export async function updateImportTemplate(id: string, request: ImportTemplateRequestDto): Promise<ImportTemplateDto> {
  return apiFetchJson(
    `${BASE}/saved/${id}`,
    { method: "PUT", headers: JSON_HEADERS, body: JSON.stringify(request) },
    "Could not save the template."
  )
}

export async function deleteImportTemplate(id: string): Promise<void> {
  await apiFetch(`${BASE}/saved/${id}`, { method: "DELETE" }, "Could not delete the template.")
}

/** GET /import-templates/saved/{id}/download - the saved template's file over the current fields. */
export async function downloadSavedImportTemplate(id: string, format: TemplateFormat): Promise<DownloadedFile> {
  const response = await apiFetch(`${BASE}/saved/${id}/download?format=${format}`, undefined,
    "Could not download the template.")
  const blob = await response.blob()
  const header = response.headers.get("Content-Disposition") ?? ""
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(header)
  return { blob, filename: match ? decodeURIComponent(match[1]) : `import-template.${format}` }
}
