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
export const FINISHED_STATUSES: readonly BulkImportStatus[] = [
  "completed",
  "completed_with_errors",
  "failed",
]

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
  /**
   * OCR progress, or null when this import did not ask for OCR. Null and
   * all-zeroes mean different things - "no OCR here" vs "OCR hasn't started" -
   * so the screen must check for null rather than for queued === 0.
   */
  ocr: BulkImportOcrProgressDto | null
}

/**
 * Progress of a bulk import's OCR.
 *
 * `queued` is NOT totalRecords: the per-batch cap and the tenant's queue
 * limit both stop at a ceiling, so progress is shown against what was
 * actually queued. Measuring against totalRecords would leave a large import
 * stuck short of 100% with nothing wrong.
 */
export interface BulkImportOcrProgressDto {
  queued: number
  pending: number
  completed: number
  failed: number
  /**
   * The first `skippedTotal` (capped server-side) files the per-batch OCR
   * cap turned away, by name, in the template's own row order. Named rather
   * than counted so they can be found and run individually — a bare number
   * says something was skipped without saying what.
   */
  skippedFilenames: string[]
  /** How many files were skipped in all — may exceed `skippedFilenames.length`. */
  skippedTotal: number
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

/**
 * GET /import-templates/document-types - the types a template can be
 * downloaded for. Gated on the import permission, unlike the full
 * /document-types list (which needs metadata-management access).
 */
export async function fetchImportDocumentTypes(): Promise<
  ImportDocumentTypeOptionDto[]
> {
  return apiFetchJson<ImportDocumentTypeOptionDto[]>(
    `${API_BASE_URL}/api/v1/import-templates/document-types`,
    undefined,
    "Could not load document types."
  )
}

/** Template (and failed-rows report) file formats. */
export type TemplateFormat = "xlsx" | "csv"

/** GET /import-templates - the template for a document type's current schema, as .xlsx or .csv. */
export async function downloadImportTemplate(
  documentTypeId: string,
  format: TemplateFormat = "xlsx"
): Promise<DownloadedFile> {
  return downloadFile(
    `${API_BASE_URL}/api/v1/import-templates?documentTypeId=${encodeURIComponent(documentTypeId)}&format=${format}`,
    "Could not download the template.",
    `import-template.${format}`
  )
}

/** GET /bulk-imports/{batchId}/failures/template - the failed rows as a template to fix and import again. */
export async function downloadFailedRowsTemplate(
  batchId: string,
  format: TemplateFormat
): Promise<DownloadedFile> {
  return downloadFile(
    `${BASE}/${batchId}/failures/template?format=${format}`,
    "Could not download the failed rows.",
    `failed-rows.${format}`
  )
}

async function downloadFile(
  url: string,
  fallbackMessage: string,
  fallbackName: string
): Promise<DownloadedFile> {
  const response = await apiFetch(url, undefined, fallbackMessage)
  const blob = await response.blob()
  const filename =
    filenameFromContentDisposition(
      response.headers.get("Content-Disposition")
    ) ?? fallbackName
  return { blob, filename }
}

/** Response of the ZIP endpoints - what happened to each entry. */
export interface ImportArchiveReceivedResponseDto {
  entriesRead: number
  filesMatched: number
  problemCount: number
  /** At most the first 200 entries that weren't staged, and why. */
  problems: { entry: string; message: string }[]
  filesReceived: number
  totalRecords: number
}

/**
 * Largest ZIP sent in one request - the backend's multipart limit is
 * 100 MB, so anything bigger goes through a resumable upload session.
 */
export const MAX_DIRECT_ARCHIVE_BYTES = 95 * 1024 * 1024

/** Largest ZIP accepted at all (dms.import-export.max-archive-size). */
export const MAX_ARCHIVE_BYTES = 2 * 1024 * 1024 * 1024

/** POST /bulk-imports/{batchId}/archive - one ZIP holding the template's files. */
export async function uploadBulkImportArchive(
  batchId: string,
  archive: File
): Promise<ImportArchiveReceivedResponseDto> {
  const body = new FormData()
  body.append("archive", archive)
  return apiFetchJson<ImportArchiveReceivedResponseDto>(
    `${BASE}/${batchId}/archive`,
    { method: "POST", body },
    `Could not upload ${archive.name}.`
  )
}

/** Mirrors backend/dms-core's UploadSessionResponse - a resumable upload. */
export interface UploadSessionDto {
  uploadSessionId: string
  fileName: string
  totalSizeBytes: number
  receivedBytes: number
  status: "pending" | "uploading" | "quarantined" | "completed" | "failed"
  maxChunkBytes: number
  expiresAt: string
}

const SESSIONS = `${API_BASE_URL}/api/v1/upload-sessions`

export async function createUploadSession(
  file: File
): Promise<UploadSessionDto> {
  return apiFetchJson<UploadSessionDto>(
    SESSIONS,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ fileName: file.name, totalSizeBytes: file.size }),
    },
    "Could not start the upload."
  )
}

export async function fetchUploadSession(
  sessionId: string
): Promise<UploadSessionDto> {
  return apiFetchJson<UploadSessionDto>(
    `${SESSIONS}/${sessionId}`,
    undefined,
    "Could not check the upload."
  )
}

/** PUT one chunk at `offset` (must equal the bytes received so far). */
export async function uploadSessionChunk(
  sessionId: string,
  offset: number,
  chunk: Blob
): Promise<UploadSessionDto> {
  return apiFetchJson<UploadSessionDto>(
    `${SESSIONS}/${sessionId}/content?offset=${offset}`,
    {
      method: "PUT",
      headers: { "Content-Type": "application/octet-stream" },
      body: chunk,
    },
    "A part of the upload failed."
  )
}

/** POST .../archive/from-upload-session - unpacks a completed resumable upload into the batch. */
export async function unpackUploadSession(
  batchId: string,
  sessionId: string
): Promise<ImportArchiveReceivedResponseDto> {
  return apiFetchJson<ImportArchiveReceivedResponseDto>(
    `${BASE}/${batchId}/archive/from-upload-session?uploadSessionId=${encodeURIComponent(sessionId)}`,
    { method: "POST" },
    "Could not unpack the ZIP."
  )
}

/** GET /bulk-imports/{batchId}/row-check - the template's rows with problems, checked now (before submit). */
export async function fetchBulkImportRowCheck(
  batchId: string
): Promise<ImportRowFailureDto[]> {
  return apiFetchJson<ImportRowFailureDto[]>(
    `${BASE}/${batchId}/row-check`,
    undefined,
    "Could not check the rows."
  )
}

/** What a bulk import does with a file that's already in the DMS (V65). */
export type DuplicateAction = "new_version" | "keep_both" | "skip"

/**
 * POST /bulk-imports - uploads the filled-in template (.xlsx or .csv) and opens a batch.
 *
 * @param ocrFields field keys to extract with OCR from every file in the
 *   batch. Empty or omitted means no OCR. The server validates them against
 *   the document type's OCR-extractable fields and rejects the whole upload
 *   with IMPORT_OCR_FIELDS_INVALID if one is not - deliberately, so a typo is
 *   reported to the person who made it instead of silently extracting nothing
 *   from every file.
 */
export async function createBulkImport(
  template: File,
  folderId?: string | null,
  duplicateAction: DuplicateAction = "keep_both",
  ocrFields?: readonly string[]
): Promise<BulkImportCreatedResponseDto> {
  const body = new FormData()
  body.append("template", template)
  const params = new URLSearchParams({ duplicateAction })
  if (folderId) params.set("folderId", folderId)
  // Repeated key, not a comma-joined string: Spring binds List<String> from
  // repeated params, and a field key containing a comma would otherwise split
  // into two keys that match nothing.
  for (const key of ocrFields ?? []) params.append("ocrFields", key)
  return apiFetchJson<BulkImportCreatedResponseDto>(
    `${BASE}?${params.toString()}`,
    { method: "POST", body },
    "Could not upload the template."
  )
}

/**
 * POST /bulk-imports/{batchId}/files - stages one file. The server locks
 * the batch for the duration of the request, so callers should send these
 * one at a time.
 */
export async function uploadBulkImportFile(
  batchId: string,
  file: File
): Promise<ImportFileReceivedResponseDto> {
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
  await apiFetch(
    `${BASE}/${batchId}/submit`,
    { method: "POST" },
    "Could not submit this import."
  )
}

/** POST /bulk-imports/{batchId}/cancel - allowed while uploading or queued. */
export async function cancelBulkImport(
  batchId: string
): Promise<BulkImportStatusResponseDto> {
  return apiFetchJson<BulkImportStatusResponseDto>(
    `${BASE}/${batchId}/cancel`,
    { method: "POST" },
    "Could not cancel this import."
  )
}

export async function fetchBulkImportStatus(
  batchId: string
): Promise<BulkImportStatusResponseDto> {
  return apiFetchJson<BulkImportStatusResponseDto>(
    `${BASE}/${batchId}`,
    undefined,
    "Could not load this import."
  )
}

/** GET /bulk-imports/{batchId}/failures - the rows that failed and why. */
export async function fetchBulkImportFailures(
  batchId: string
): Promise<ImportRowFailureDto[]> {
  return apiFetchJson<ImportRowFailureDto[]>(
    `${BASE}/${batchId}/failures`,
    undefined,
    "Could not load the rows that failed."
  )
}

/** Mirrors PageResponse<BulkImportStatusResponse>. */
export interface BulkImportPageDto {
  content: BulkImportStatusResponseDto[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  last: boolean
}

/** GET /bulk-imports - the caller's own imports, newest first, finished ones included. `page` is 0-indexed. */
export async function fetchMyBulkImports(
  page: number,
  size: number
): Promise<BulkImportPageDto> {
  return apiFetchJson<BulkImportPageDto>(
    `${BASE}?page=${page}&size=${size}&sort=createdAt,desc`,
    undefined,
    "Could not load your recent imports."
  )
}

/** GET /bulk-imports/active - the caller's in-progress batch, or `null` (204) when there is none. */
export async function fetchActiveBulkImport(): Promise<BulkImportStatusResponseDto | null> {
  const response = await apiFetch(
    `${BASE}/active`,
    undefined,
    "Could not check for an import in progress."
  )
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
