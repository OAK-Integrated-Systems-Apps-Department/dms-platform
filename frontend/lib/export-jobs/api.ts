import { API_BASE_URL } from "@/lib/api-config"
import { apiFetch, apiFetchJson } from "@/lib/api-fetch"
import { type DownloadedFile } from "@/lib/bulk-imports/api"

const BASE = `${API_BASE_URL}/api/v1/export-jobs`

/** Mirrors backend/dms-core's ExportType (export_jobs.export_type). */
export type ExportType =
  "single_document" | "bulk_selection" | "bulk_folder" | "report_data"

/** Mirrors backend/dms-core's ExportJobStatus (export_jobs.status). */
export type ExportJobStatus =
  "pending" | "processing" | "completed" | "failed" | "expired"

/** The metadata sheet's format inside the export ZIP. */
export type ExportMetadataFormat = "xlsx" | "csv"

/**
 * Mirrors backend/dms-core's ExportJobResponse - one "My exports" row. Never
 * carries the storage key; `downloadAvailable` says whether the finished
 * ZIP can be downloaded now (ready and not expired).
 */
export interface ExportJobDto {
  exportJobId: string
  exportType: ExportType
  status: ExportJobStatus
  createdAt: string
  updatedAt: string
  expiresAt: string | null
  downloadAvailable: boolean
  metadataFormat: ExportMetadataFormat
  documentCount: number
  fileSizeBytes: number | null
  /** Why it failed - or, on a finished export, which files couldn't be included. */
  failureReason: string | null
  completedAt: string | null
}

/** Mirrors backend/dms-core's PageResponse<ExportJobResponse>. */
export interface ExportJobPageDto {
  content: ExportJobDto[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  last: boolean
}

/** Mirrors CreateExportRequest. Dates are yyyy-MM-dd, inclusive. */
export interface CreateExportRequestDto {
  documentIds?: string[]
  /** A folder's documents - with its subfolders unless includeSubfolders is false. */
  folderId?: string
  includeSubfolders?: boolean
  documentTypeId?: string
  createdFrom?: string
  createdTo?: string
  metadataFormat: ExportMetadataFormat
  includeFiles: boolean
}

export interface ExportPreviewDto {
  documentCount: number
  maxDocuments: number
}

export interface ExportDocumentTypeOptionDto {
  documentTypeId: string
  name: string
  typeCode: string
}

/** Not finished yet - worth polling. */
export function isExportInFlight(status: ExportJobStatus): boolean {
  return status === "pending" || status === "processing"
}

/**
 * The signed-in user's own export jobs (`GET /api/v1/export-jobs`) - the
 * backend scopes to the caller's tenant and user itself. `page` is 0-indexed.
 */
export async function fetchMyExportJobs(
  status: ExportJobStatus | undefined,
  page: number,
  size: number
): Promise<ExportJobPageDto> {
  const params = new URLSearchParams()
  if (status) params.set("status", status)
  params.set("page", String(page))
  params.set("size", String(size))
  params.set("sort", "createdAt,desc")
  return apiFetchJson(
    `${BASE}?${params.toString()}`,
    undefined,
    "Could not load your exports."
  )
}

export async function fetchExportDocumentTypes(): Promise<
  ExportDocumentTypeOptionDto[]
> {
  return apiFetchJson(
    `${BASE}/document-types`,
    undefined,
    "Could not load document types."
  )
}

const JSON_HEADERS = { "Content-Type": "application/json" }

/** POST /export-jobs/preview - how many documents the export would hold; nothing is started. */
export async function previewExport(
  request: CreateExportRequestDto
): Promise<ExportPreviewDto> {
  return apiFetchJson(
    `${BASE}/preview`,
    { method: "POST", headers: JSON_HEADERS, body: JSON.stringify(request) },
    "Could not count the documents."
  )
}

/** POST /export-jobs - starts the export in the background. */
export async function requestExport(
  request: CreateExportRequestDto
): Promise<ExportJobDto> {
  return apiFetchJson(
    BASE,
    { method: "POST", headers: JSON_HEADERS, body: JSON.stringify(request) },
    "Could not start the export."
  )
}

/** GET /export-jobs/{id}/download - the finished ZIP. */
export async function downloadExport(
  exportJobId: string
): Promise<DownloadedFile> {
  const response = await apiFetch(
    `${BASE}/${exportJobId}/download`,
    undefined,
    "Could not download the export."
  )
  const blob = await response.blob()
  const header = response.headers.get("Content-Disposition") ?? ""
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(header)
  return { blob, filename: match ? decodeURIComponent(match[1]) : "export.zip" }
}
