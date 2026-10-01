import { API_BASE_URL } from "@/lib/api-config"
import { apiFetchJson } from "@/lib/api-fetch"

const BASE = `${API_BASE_URL}/api/v1/export-jobs`

/** Mirrors backend/dms-core's ExportType (export_jobs.export_type). */
export type ExportType = "single_document" | "bulk_folder" | "report_data"

/** Mirrors backend/dms-core's ExportJobStatus (export_jobs.status). */
export type ExportJobStatus = "pending" | "processing" | "completed" | "failed" | "expired"

/**
 * Mirrors backend/dms-core's ExportJobResponse - one "My exports" row. Never
 * carries the storage key; `downloadAvailable` says whether a finished file
 * exists (downloading it arrives with the export feature itself, Phase 4).
 */
export interface ExportJobDto {
  exportJobId: string
  exportType: ExportType
  status: ExportJobStatus
  createdAt: string
  updatedAt: string
  expiresAt: string | null
  downloadAvailable: boolean
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
  return apiFetchJson(`${BASE}?${params.toString()}`, undefined, "Could not load your exports.")
}
