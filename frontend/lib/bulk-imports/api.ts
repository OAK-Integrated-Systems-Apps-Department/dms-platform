import { API_BASE_URL } from "@/lib/api-config"
import { apiFetch, apiFetchJson } from "@/lib/api-fetch"
import { downloadBlob, filenameFromContentDisposition } from "@/lib/download"

/** Mirrors ImportBatch.ImportBatchStatus on the backend. */
export type BulkImportStatus =
  | "uploading"
  | "queued"
  | "running"
  | "completed"
  | "completed_with_errors"
  | "failed"
  | "cancelled"

/**
 * Mirrors ImportBatch.CancelReason: `user` cancelled it, or it `expired` after a
 * period with no uploads. Null on a cancelled batch means the user cancelled it.
 */
export type BulkImportCancelReason = "user" | "expired"

/** Statuses the worker has finished with - record creation is over. */
export const FINISHED_STATUSES: readonly BulkImportStatus[] = ["completed", "completed_with_errors", "failed"]

/** Submitted and not yet finished - worth polling for progress. */
export function isInFlight(status: BulkImportStatus | undefined): boolean {
  return status === "queued" || status === "running"
}

export function isFinished(status: BulkImportStatus | undefined): boolean {
  return status !== undefined && FINISHED_STATUSES.includes(status)
}

/** Finished with at least some files not imported - worth listing what failed. */
export function hasFailedRows(status: BulkImportStatus | undefined): boolean {
  return status === "completed_with_errors" || status === "failed"
}

/** Response of POST /bulk-imports - the batch is open for uploads. */
export interface BulkImportCreatedResponseDto {
  batchId: string
  documentTypeId: string
  /** The type's schema version the batch was created at - its defaults are the ones applied. */
  schemaVersion: number
  /** How many files (accepted and rejected) the batch can take. */
  maxFiles: number
}

/** Response of POST /bulk-imports/{batchId}/files for an accepted file. A rejected one gets a 4xx instead. */
export interface ImportFileReceivedResponseDto {
  /** The item this upload became. */
  rowNumber: number
  /** The file's name as kept, without any folder path. */
  filename: string
  /** Uploads accepted into the batch so far. */
  filesReceived: number
  /** Uploads refused so far. */
  filesRejected: number
  /** Every upload so far, accepted or rejected. */
  totalFiles: number
}

/** Response of GET /bulk-imports/{batchId}, /bulk-imports/active and POST .../cancel. */
export interface BulkImportStatusResponseDto {
  batchId: string
  documentTypeId: string
  status: BulkImportStatus
  /** Every upload into the batch, accepted or rejected. */
  totalRecords: number
  /** Uploads that were accepted. */
  filesReceived: number
  /** Uploads that were refused - each is a failed item in the results. */
  filesRejected: number
  successCount: number
  failureCount: number
  /** The refused uploads' names, in upload order. */
  rejectedFilenames: string[]
  createdAt: string
  /** Set on a cancelled batch; null otherwise. */
  cancelReason: BulkImportCancelReason | null
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
  /** Set on a cancelled batch; null otherwise. */
  cancelReason: BulkImportCancelReason | null
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

/** One problem with a failed file. `fieldLabel` is the column's display name (null if the problem isn't about one column). */
export interface ImportRowProblemDto {
  field: string | null
  fieldLabel: string | null
  code: string
  message: string
  /** "error" failed the row; "warning" is only reported. */
  severity: "error" | "warning"
}

/** Response item of GET /bulk-imports/{batchId}/failures - failed files only, in upload order. */
export interface ImportRowFailureDto {
  rowNumber: number
  filename: string
  /** The document title taken from the filename; null if there was none. */
  title: string | null
  problems: ImportRowProblemDto[]
}

/** A default value an import applies to every document. `value` is as configured, before conversion. */
export interface ImportDefaultValueDto {
  fieldKey: string
  label: string
  value: string
}

/**
 * A configured default an import won't apply: it doesn't convert, match one of
 * the field's active options, or pass the field's validation. `reason` has no
 * full stop.
 */
export interface ImportInvalidDefaultDto {
  fieldKey: string
  label: string
  value: string
  reason: string
}

export interface ImportFieldDto {
  fieldKey: string
  label: string
}

/**
 * Response of GET /bulk-imports/document-types/{id}/defaults - the metadata an
 * import into the type would apply right now (its current schema version).
 * Mandatory fields with no default are left empty, unless
 * `enforceMandatoryFields`, when the import can't be started.
 */
export interface ImportDefaultsDto {
  documentTypeId: string
  schemaVersion: number
  defaults: ImportDefaultValueDto[]
  invalidDefaults: ImportInvalidDefaultDto[]
  mandatoryFieldsWithoutDefault: ImportFieldDto[]
  enforceMandatoryFields: boolean
}

/** Response item of GET /import-templates/document-types - active types only, in display order. */
export interface ImportDocumentTypeOptionDto {
  documentTypeId: string
  name: string
  typeCode: string
}

const BASE = `${API_BASE_URL}/api/v1/bulk-imports`

/**
 * GET /import-templates/document-types - the types a bulk import can be
 * started for. Gated on the import permission, unlike the full
 * /document-types list (which needs metadata-management access). The path
 * predates the files-first flow; the backend kept it.
 */
export async function fetchImportDocumentTypes(): Promise<ImportDocumentTypeOptionDto[]> {
  return apiFetchJson<ImportDocumentTypeOptionDto[]>(
    `${API_BASE_URL}/api/v1/import-templates/document-types`,
    undefined,
    "Could not load document types."
  )
}

/** GET /bulk-imports/document-types/{id}/defaults - what an import into the type would apply. */
export async function fetchImportDefaults(documentTypeId: string): Promise<ImportDefaultsDto> {
  return apiFetchJson<ImportDefaultsDto>(
    `${BASE}/document-types/${encodeURIComponent(documentTypeId)}/defaults`,
    undefined,
    "Could not load the default values for this document type."
  )
}

/** POST /bulk-imports - opens a batch for a document type; its files are uploaded next. */
export async function createBulkImport(documentTypeId: string): Promise<BulkImportCreatedResponseDto> {
  return apiFetchJson<BulkImportCreatedResponseDto>(
    BASE,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ documentTypeId }),
    },
    "Could not start the import."
  )
}

/**
 * POST /bulk-imports/{batchId}/files - stages one file. The server locks
 * the batch for the duration of the request, so callers should send these
 * one at a time. `clientFileId` identifies this file: resending it with the
 * same ID (a retry) gets the original outcome instead of a second item.
 */
export async function uploadBulkImportFile(
  batchId: string,
  file: File,
  clientFileId: string
): Promise<ImportFileReceivedResponseDto> {
  const body = new FormData()
  body.append("file", file)
  body.append("clientFileId", clientFileId)
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

/** GET /bulk-imports/{batchId}/failures - the files that failed and why. */
export async function fetchBulkImportFailures(batchId: string): Promise<ImportRowFailureDto[]> {
  return apiFetchJson<ImportRowFailureDto[]>(
    `${BASE}/${batchId}/failures`,
    undefined,
    "Could not load the files that failed."
  )
}

/**
 * GET /bulk-imports/{batchId}/failures.csv - downloads the failure report
 * as CSV (one line per problem). The server records the download.
 */
export async function exportBulkImportFailuresCsv(batchId: string): Promise<void> {
  const response = await apiFetch(
    `${BASE}/${batchId}/failures.csv`,
    undefined,
    "Could not export the failure report."
  )
  const blob = await response.blob()
  downloadBlob(blob, filenameFromContentDisposition(response.headers.get("Content-Disposition")) ?? "import-failures.csv")
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
