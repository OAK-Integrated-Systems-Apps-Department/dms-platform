import { API_BASE_URL } from "@/lib/api-config"
import { apiFetch, apiFetchJson } from "@/lib/api-fetch"

/** Mirrors ImportBatch.ImportBatchStatus on the backend. */
export type BulkImportStatus =
  | "uploading"
  | "queued"
  | "running"
  | "completed"
  | "completed_with_errors"
  | "failed"
  | "cancelled"

/** Statuses the worker has finished with - record creation is over. */
export const FINISHED_STATUSES: readonly BulkImportStatus[] = ["completed", "completed_with_errors", "failed"]

/** Submitted and not yet finished - worth polling for progress. */
export function isInFlight(status: BulkImportStatus | undefined): boolean {
  return status === "queued" || status === "running"
}

export function isFinished(status: BulkImportStatus | undefined): boolean {
  return status !== undefined && FINISHED_STATUSES.includes(status)
}

/** Finished with at least some rows not imported - worth listing what failed. */
export function hasFailedRows(status: BulkImportStatus | undefined): boolean {
  return status === "completed_with_errors" || status === "failed"
}

/** Response of POST /bulk-imports. `expectedFilenames` has one entry per data row, in row order. */
export interface BulkImportCreatedResponseDto {
  batchId: string
  totalRecords: number
  expectedFilenames: string[]
}

/** Response of POST /bulk-imports/{batchId}/files. */
export interface ImportFileReceivedResponseDto {
  rowNumber: number
  filesReceived: number
  totalRecords: number
}

/** Response of GET /bulk-imports/{batchId}, /bulk-imports/active and POST .../cancel. */
export interface BulkImportStatusResponseDto {
  batchId: string
  documentTypeId: string
  status: BulkImportStatus
  totalRecords: number
  filesReceived: number
  successCount: number
  failureCount: number
  /** One entry per row still waiting for its file. */
  missingFilenames: string[]
  createdAt: string
}

/** Item of GET /bulk-imports - one of the caller's batches, newest first. */
export interface BulkImportSummaryDto {
  batchId: string
  documentTypeId: string
  /** Null if the type can't be found any more. */
  documentTypeName: string | null
  status: BulkImportStatus
  totalRecords: number
  successCount: number
  failureCount: number
  createdAt: string
  /** Set once the worker has finished the batch; null before (and for a cancelled batch). */
  completedAt: string | null
}

/** Mirrors backend/dms-core's PageResponse<BulkImportSummary>. `page` is 0-indexed. */
export interface BulkImportPageDto {
  content: BulkImportSummaryDto[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  last: boolean
}

/** One problem with a failed row. `fieldLabel` is the column's display name (null if the problem isn't about one column). */
export interface ImportRowProblemDto {
  field: string | null
  fieldLabel: string | null
  code: string
  message: string
  /** "error" failed the row; "warning" is only reported. */
  severity: "error" | "warning"
}

/** Response item of GET /bulk-imports/{batchId}/failures - failed rows only, in row order. */
export interface ImportRowFailureDto {
  rowNumber: number
  filename: string
  /** The raw title cell; null if it was left blank. */
  title: string | null
  problems: ImportRowProblemDto[]
}

/** Response item of GET /import-templates/document-types - active types only, in display order. */
export interface ImportDocumentTypeOptionDto {
  documentTypeId: string
  name: string
  typeCode: string
}

export interface DownloadedFile {
  blob: Blob
  filename: string
}

const BASE = `${API_BASE_URL}/api/v1/bulk-imports`
const TEMPLATE_FALLBACK_FILENAME = "import-template.xlsx"

/**
 * GET /import-templates/document-types - the types a template can be
 * downloaded for. Gated on the import permission, unlike the full
 * /document-types list (which needs metadata-management access).
 */
export async function fetchImportDocumentTypes(): Promise<ImportDocumentTypeOptionDto[]> {
  return apiFetchJson<ImportDocumentTypeOptionDto[]>(
    `${API_BASE_URL}/api/v1/import-templates/document-types`,
    undefined,
    "Could not load document types."
  )
}

/** GET /import-templates - the .xlsx template for a document type's current schema. */
export async function downloadImportTemplate(documentTypeId: string): Promise<DownloadedFile> {
  const response = await apiFetch(
    `${API_BASE_URL}/api/v1/import-templates?documentTypeId=${encodeURIComponent(documentTypeId)}`,
    undefined,
    "Could not download the template."
  )
  const blob = await response.blob()
  const filename =
    filenameFromContentDisposition(response.headers.get("Content-Disposition")) ?? TEMPLATE_FALLBACK_FILENAME
  return { blob, filename }
}

/** POST /bulk-imports - uploads the filled-in template and opens a batch. */
export async function createBulkImport(template: File): Promise<BulkImportCreatedResponseDto> {
  const body = new FormData()
  body.append("template", template)
  return apiFetchJson<BulkImportCreatedResponseDto>(
    BASE,
    { method: "POST", body },
    "Could not upload the template."
  )
}

/**
 * POST /bulk-imports/{batchId}/files - stages one file. The server locks
 * the batch for the duration of the request, so callers should send these
 * one at a time.
 */
export async function uploadBulkImportFile(batchId: string, file: File): Promise<ImportFileReceivedResponseDto> {
  const body = new FormData()
  body.append("file", file)
  return apiFetchJson<ImportFileReceivedResponseDto>(
    `${BASE}/${batchId}/files`,
    { method: "POST", body },
    `Could not upload ${file.name}.`
  )
}

/** POST /bulk-imports/{batchId}/submit - queues the batch (202, no body). */
export async function submitBulkImport(batchId: string): Promise<void> {
  await apiFetch(`${BASE}/${batchId}/submit`, { method: "POST" }, "Could not submit this import.")
}

/** POST /bulk-imports/{batchId}/cancel - allowed while uploading or queued. */
export async function cancelBulkImport(batchId: string): Promise<BulkImportStatusResponseDto> {
  return apiFetchJson<BulkImportStatusResponseDto>(
    `${BASE}/${batchId}/cancel`,
    { method: "POST" },
    "Could not cancel this import."
  )
}

export async function fetchBulkImportStatus(batchId: string): Promise<BulkImportStatusResponseDto> {
  return apiFetchJson<BulkImportStatusResponseDto>(`${BASE}/${batchId}`, undefined, "Could not load this import.")
}

/** GET /bulk-imports/{batchId}/failures - the rows that failed and why. */
export async function fetchBulkImportFailures(batchId: string): Promise<ImportRowFailureDto[]> {
  return apiFetchJson<ImportRowFailureDto[]>(
    `${BASE}/${batchId}/failures`,
    undefined,
    "Could not load the rows that failed."
  )
}

/** GET /bulk-imports - one page of the caller's batches in every status, newest first. The server caps `size` at 50. */
export async function fetchBulkImports(page: number, size: number): Promise<BulkImportPageDto> {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  return apiFetchJson<BulkImportPageDto>(`${BASE}?${params}`, undefined, "Could not load your recent imports.")
}

/** GET /bulk-imports/active - the caller's in-progress batch, or `null` (204) when there is none. */
export async function fetchActiveBulkImport(): Promise<BulkImportStatusResponseDto | null> {
  const response = await apiFetch(`${BASE}/active`, undefined, "Could not check for an import in progress.")
  if (response.status === 204) return null
  return (await response.json()) as BulkImportStatusResponseDto
}

/** Hands a downloaded blob to the browser as a file save. */
export function saveDownloadedFile({ blob, filename }: DownloadedFile) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement("a")
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}

/**
 * Prefers RFC 5987 `filename*=UTF-8''...` (what Spring's ContentDisposition
 * emits for non-ASCII names) over the plain `filename="..."` parameter.
 */
function filenameFromContentDisposition(header: string | null): string | null {
  if (!header) return null
  const extended = /filename\*\s*=\s*(?:[\w-]+'[^']*')?([^;]+)/i.exec(header)
  if (extended) {
    try {
      return decodeURIComponent(extended[1].trim().replace(/^"|"$/g, ""))
    } catch {
      // Malformed encoding - fall through to the plain parameter.
    }
  }
  const plain = /filename\s*=\s*"?([^";]+)"?/i.exec(header)
  return plain ? plain[1].trim() : null
}
