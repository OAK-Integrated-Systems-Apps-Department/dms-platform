import { API_BASE_URL } from "@/lib/api-config"
import { ApiError, friendlyMessage } from "@/lib/api-error"
import { apiFetch, apiFetchJson } from "@/lib/api-fetch"
import { downloadBlob, filenameFromContentDisposition } from "@/lib/download"

/** Mirrors ExportJob.ExportJobStatus on the backend. */
export type ExportJobStatus = "pending" | "processing" | "completed" | "failed" | "expired"

/** Mirrors ExportJob.MetadataFormat - the metadata sheet inside the ZIP. */
export type MetadataFormat = "xlsx" | "csv"

/** Queued or being built - worth polling. */
export function isPendingStatus(status: ExportJobStatus | undefined): boolean {
  return status === "pending" || status === "processing"
}

/** Mirrors backend/dms-core's PageResponse. `page` is 0-indexed. */
export interface PageDto<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  last: boolean
}

/** Mirrors BulkExportFilters. `createdFrom` is inclusive, `createdTo` exclusive; both ISO date-times with an offset. */
export interface BulkExportFiltersDto {
  documentTypeId?: string
  createdFrom?: string
  createdTo?: string
  uploadedBy?: string
  titleContains?: string
  importBatchId?: string
}

/** Mirrors BulkExportSelection - exactly one of the two is set. */
export type BulkExportSelectionDto = { documentIds: string[] } | { filters: BulkExportFiltersDto }

/** Mirrors ExportableDocumentResponse. */
export interface ExportableDocumentDto {
  documentId: string
  title: string
  documentTypeId: string | null
  documentTypeName: string | null
  uploadedById: string | null
  uploadedByName: string | null
  createdAt: string
  sizeBytes: number | null
}

/** Mirrors BulkExportPreviewResponse. `withinLimits` is false for an empty selection too. */
export interface BulkExportPreviewDto {
  documentCount: number
  totalBytes: number
  maxDocuments: number
  maxBytes: number
  withinLimits: boolean
}

/** Mirrors BulkExportJobResponse. */
export interface BulkExportJobDto {
  exportJobId: string
  status: ExportJobStatus
  metadataFormat: MetadataFormat
  documentCount: number
  skippedCount: number
  fileSizeBytes: number | null
  failureReason: string | null
  createdAt: string
  completedAt: string | null
  expiresAt: string | null
  /** Completed and not yet expired. */
  downloadAvailable: boolean
}

/** Mirrors ImportDocumentTypeOption, served under the export permissions by GET /bulk-exports/document-types. */
export interface ExportDocumentTypeOptionDto {
  documentTypeId: string
  name: string
  typeCode: string
}

const BASE = `${API_BASE_URL}/api/v1/bulk-exports`

const JSON_HEADERS = { "Content-Type": "application/json" }

export async function fetchExportDocumentTypes(): Promise<ExportDocumentTypeOptionDto[]> {
  return apiFetchJson<ExportDocumentTypeOptionDto[]>(
    `${BASE}/document-types`,
    undefined,
    "Could not load the document types."
  )
}

export async function fetchExportableDocuments(
  filters: BulkExportFiltersDto,
  page: number,
  size: number
): Promise<PageDto<ExportableDocumentDto>> {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  for (const [key, value] of Object.entries(filters)) {
    if (value) params.set(key, value)
  }
  return apiFetchJson<PageDto<ExportableDocumentDto>>(
    `${BASE}/documents?${params}`,
    undefined,
    "Could not load the documents."
  )
}

export async function previewExport(selection: BulkExportSelectionDto): Promise<BulkExportPreviewDto> {
  return apiFetchJson<BulkExportPreviewDto>(
    `${BASE}/preview`,
    { method: "POST", headers: JSON_HEADERS, body: JSON.stringify(selection) },
    "Could not check the selection."
  )
}

export async function createExport(
  selection: BulkExportSelectionDto,
  metadataFormat: MetadataFormat
): Promise<BulkExportJobDto> {
  return apiFetchJson<BulkExportJobDto>(
    BASE,
    { method: "POST", headers: JSON_HEADERS, body: JSON.stringify({ selection, metadataFormat }) },
    "Could not start the export."
  )
}

export async function fetchMyExports(page: number, size: number): Promise<PageDto<BulkExportJobDto>> {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  return apiFetchJson<PageDto<BulkExportJobDto>>(`${BASE}?${params}`, undefined, "Could not load your exports.")
}

export async function downloadExport(exportJobId: string): Promise<void> {
  const response = await apiFetch(`${BASE}/${exportJobId}/download`, undefined, "Could not download the export.")
  const blob = await response.blob()
  downloadBlob(blob, filenameFromContentDisposition(response.headers.get("Content-Disposition")) ?? "export.zip")
}

/** Codes from BulkExportSelectionException / ActiveExportLimitException / ExportFileUnavailableException. */
const ERROR_MESSAGES: Record<string, string> = {
  EXPORT_ACTIVE_LIMIT:
    "You already have an export in progress. Wait for it to finish before starting another.",
  EXPORT_NOTHING_SELECTED: "None of the selected documents can be exported any more. Refresh and try again.",
  EXPORT_TOO_MANY_DOCUMENTS: "The selection has more documents than one export allows. Narrow it and try again.",
  EXPORT_TOO_LARGE: "The selection is larger than one export allows. Narrow it and try again.",
  EXPORT_NOT_READY: "This export isn't ready to download yet.",
  EXPORT_EXPIRED: "This export has expired. Request a new one.",
}

/** A user-facing message for a failed export call - the backend's code where we know it, otherwise its message. */
export function exportErrorMessage(error: unknown): string {
  if (error instanceof ApiError && error.code && ERROR_MESSAGES[error.code]) {
    return ERROR_MESSAGES[error.code]
  }
  return friendlyMessage(error)
}
