import { API_BASE_URL } from "@/lib/api-config"
import { apiFetch, apiFetchJson } from "@/lib/api-fetch"

const BASE = `${API_BASE_URL}/api/v1/quarantine`

export type QuarantineStatus = "held" | "released" | "deleted"

/** Mirrors backend/dms-core's QuarantineUserSummary. */
export interface QuarantineUserDto {
  userId: string
  displayName: string | null
}

/** Mirrors backend/dms-core's QuarantineRecordResponse - one list row. Never carries a storage key: quarantined bytes are not reachable through any API. */
export interface QuarantineRecordDto {
  quarantineId: string
  originalFilename: string
  threatName: string
  sizeBytes: number
  status: QuarantineStatus
  detectedAt: string
  uploadedBy: QuarantineUserDto | null
  reviewedBy: QuarantineUserDto | null
  reviewedAt: string | null
}

/** Mirrors backend/dms-core's QuarantineScanDetail - the scan that quarantined the file. Engine/signature versions are null for the cloud scanners. */
export interface QuarantineScanDetailDto {
  scanResultId: string
  threatName: string | null
  engineName: string | null
  engineVersion: string | null
  signatureVersion: string | null
  scanMode: "pre_store_stream" | "post_store_object"
  scannedAt: string | null
  durationMs: number | null
  mimeType: string
}

export type ReleaseAttemptStatus =
  | "pending"
  | "infected"
  | "clean"
  | "quarantined"
  | "released_override"
  | "failed"
  | "unscannable"

/** Mirrors backend/dms-core's QuarantineReleaseAttemptResponse. */
export interface QuarantineReleaseAttemptDto {
  scanResultId: string
  kind: "rescan" | "override"
  status: ReleaseAttemptStatus
  requestedBy: QuarantineUserDto | null
  reason: string | null
  engineName: string | null
  signatureVersion: string | null
  threatName: string | null
  failureReason: string | null
  requestedAt: string
  completedAt: string | null
}

/** Mirrors backend/dms-core's QuarantineRecordDetailResponse. */
export interface QuarantineRecordDetailDto extends QuarantineRecordDto {
  fileHash: string
  reviewReason: string | null
  releasedDocumentId: string | null
  scan: QuarantineScanDetailDto | null
  releaseInProgress: boolean
  overrideAvailable: boolean
  releaseAttempts: QuarantineReleaseAttemptDto[]
}

/** Mirrors backend/dms-core's PageResponse<QuarantineRecordResponse>. */
export interface QuarantinePageDto {
  content: QuarantineRecordDto[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  last: boolean
}

/** Mirrors backend/dms-core's QuarantineReleaseResponse. */
export interface QuarantineReleaseResultDto {
  outcome: "released" | "refused" | "pending" | "failed"
  attemptScanResultId: string | null
  documentId: string | null
  threatName: string | null
  message: string
}

/** Every filter `GET /api/v1/quarantine` accepts. detectedFrom/To are full ISO-8601 date-times. */
export interface QuarantineFilters {
  status?: QuarantineStatus
  detectedFrom?: string
  detectedTo?: string
  search?: string
}

/** One page, server-side filtered/paginated like the audit log. `page` is 0-indexed here. */
export async function fetchQuarantineRecords(
  filters: QuarantineFilters,
  page: number,
  size: number
): Promise<QuarantinePageDto> {
  const params = new URLSearchParams()
  if (filters.status) params.set("status", filters.status)
  if (filters.detectedFrom) params.set("detectedFrom", filters.detectedFrom)
  if (filters.detectedTo) params.set("detectedTo", filters.detectedTo)
  if (filters.search) params.set("search", filters.search)
  params.set("page", String(page))
  params.set("size", String(size))
  params.set("sort", "createdAt,desc")
  return apiFetchJson(`${BASE}?${params.toString()}`, undefined, "Could not load quarantined files.")
}

export async function fetchQuarantineRecord(id: string): Promise<QuarantineRecordDetailDto> {
  return apiFetchJson(`${BASE}/${id}`, undefined, "Could not load this quarantined file.")
}

function reasonBody(reason: string): RequestInit {
  return {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ reason }),
  }
}

/** Re-scans the file; releases it only if it now comes back clean. */
export async function releaseQuarantineRecord(id: string, reason: string): Promise<QuarantineReleaseResultDto> {
  return apiFetchJson(`${BASE}/${id}/release`, reasonBody(reason), "Could not release this file.")
}

/** Force-releases a file the scanner still flags. Only allowed after a refused re-scan. */
export async function overrideReleaseQuarantineRecord(id: string, reason: string): Promise<QuarantineReleaseResultDto> {
  return apiFetchJson(`${BASE}/${id}/override-release`, reasonBody(reason), "Could not override-release this file.")
}

/** Removes the file from quarantine storage permanently; the record is kept, marked deleted. */
export async function deleteQuarantineRecord(id: string, reason: string): Promise<void> {
  await apiFetch(`${BASE}/${id}/delete`, reasonBody(reason), "Could not delete this file.")
}
