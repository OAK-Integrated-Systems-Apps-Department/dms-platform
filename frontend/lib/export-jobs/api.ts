import { API_BASE_URL } from "@/lib/api-config"
import { apiFetchJson } from "@/lib/api-fetch"

const BASE = `${API_BASE_URL}/api/v1/export-jobs`

/** Mirrors backend/dms-core's ExportJobStatus (export_jobs.status). */
export type ExportJobStatus =
  "pending" | "processing" | "completed" | "failed" | "expired"

/**
 * Mirrors backend/dms-core's ExportJobResponse - one ZIP download being
 * prepared. Never carries the storage key; `downloadAvailable` says whether
 * the finished ZIP can be downloaded now (ready and not expired).
 */
export interface ExportJobDto {
  exportJobId: string
  status: ExportJobStatus
  downloadAvailable: boolean
  documentCount: number
  fileSizeBytes: number | null
  /** Why it failed - or, when finished, which files couldn't be included. */
  failureReason: string | null
}

/**
 * Mirrors CreateExportRequest: some documents, or a folder (with its
 * subfolders). The ZIP holds only the files - a folder's keep their folders.
 */
export type CreateExportRequestDto =
  { documentIds: string[] } | { folderId: string; includeSubfolders: true }

/** POST /export-jobs - starts preparing the ZIP in the background. */
export async function requestExport(
  request: CreateExportRequestDto
): Promise<ExportJobDto> {
  return apiFetchJson(
    BASE,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ ...request, includeFiles: true }),
    },
    "Could not start the download."
  )
}

/** GET /export-jobs/{id} - where it stands. */
export async function fetchExportJob(
  exportJobId: string
): Promise<ExportJobDto> {
  return apiFetchJson(
    `${BASE}/${exportJobId}`,
    undefined,
    "Could not check on the download."
  )
}

/**
 * Where the finished ZIP is served. Opened by the browser itself (see
 * startZipDownload) so a ZIP of several GB streams to disk instead of being
 * held in memory first.
 */
export function exportDownloadUrl(exportJobId: string): string {
  return `${BASE}/${exportJobId}/download`
}
