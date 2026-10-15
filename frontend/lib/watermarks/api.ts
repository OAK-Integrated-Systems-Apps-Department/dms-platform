import { API_BASE_URL } from "@/lib/api-config"
import { apiFetch, apiFetchJson } from "@/lib/api-fetch"
import { type DownloadedFile } from "@/lib/download-file"

const TEMPLATES = `${API_BASE_URL}/api/v1/watermark-templates`
const JOBS = `${API_BASE_URL}/api/v1/watermark-jobs`
const DOCUMENTS = `${API_BASE_URL}/api/v1/documents`
const SETTINGS = `${API_BASE_URL}/api/v1/watermark-settings`

const JSON_HEADERS = { "Content-Type": "application/json" }

/** Mirrors backend/dms-core's WatermarkJobStatus (watermark_jobs.status). */
export type WatermarkJobStatus =
  "pending" | "processing" | "completed" | "failed" | "expired"

/** Mirrors WatermarkPlacement's wire values. */
export type WatermarkPlacement = "tiled" | "centered" | "corner" | "header-footer"

/** Mirrors WatermarkPageScope's wire values. */
export type WatermarkPageScope = "all-pages" | "first-page-only"

/**
 * Mirrors WatermarkTemplateResponse. `systemTemplate` rows are the built-in
 * four (tenant_id NULL) and are read-only - `editable` says so explicitly
 * rather than making the UI infer it, so the rule stays the backend's.
 */
export interface WatermarkTemplateDto {
  templateId: string
  label: string
  defaultText: string
  fontSize: number | null
  opacity: number | null
  rotation: number | null
  color: string | null
  placement: string | null
  pageScope: string | null
  systemTemplate: boolean
  editable: boolean
  active: boolean
  version: number
  createdAt: string
  updatedAt: string
}

/** Mirrors WatermarkTemplateRequest. A style field left out means the system default. */
export interface WatermarkTemplateRequestDto {
  label: string
  defaultText: string
  fontSize?: number | null
  opacity?: number | null
  rotation?: number | null
  color?: string | null
  placement?: string | null
  pageScope?: string | null
  active?: boolean | null
}

/**
 * Mirrors WatermarkJobResponse. `text` is the RESOLVED watermark - every
 * placeholder already substituted and frozen onto the job, so what this
 * shows is necessarily what is on the page. `downloadAvailable` folds
 * together finished, not purged and not expired.
 */
export interface WatermarkJobDto {
  jobId: string
  documentId: string
  versionId: string
  templateId: string | null
  templateVersion: number | null
  status: WatermarkJobStatus
  text: string
  downloadAvailable: boolean
  outputSizeBytes: number | null
  expiresAt: string | null
  failureCode: string | null
  /** Written for the person who asked - safe to show as-is. */
  failureReason: string | null
  createdAt: string
  startedAt: string | null
  completedAt: string | null
}

/** Mirrors PageResponse<WatermarkJobResponse>. */
export interface WatermarkJobPageDto {
  content: WatermarkJobDto[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  last: boolean
}

/** Mirrors WatermarkRequest - exactly one of templateId or customText. */
export interface WatermarkRequestDto {
  templateId?: string | null
  customText?: string | null
  versionId?: string | null
  fontSize?: number | null
  opacity?: number | null
  rotation?: number | null
  color?: string | null
  placement?: string | null
  pageScope?: string | null
}

/** Mirrors WatermarkPreviewRequest - the same style fields, no document. */
export type WatermarkPreviewRequestDto = Omit<WatermarkRequestDto, "versionId">

/** Not finished yet - worth polling. */
export function isWatermarkInFlight(status: WatermarkJobStatus): boolean {
  return status === "pending" || status === "processing"
}

/**
 * Mirrors WatermarkDefaultsResponse. This is the tenant's policy for the
 * plain "Download" action - separate from, and unaffected by, the
 * user-initiated "Add watermark" job above. `defaultTemplateId` can be set
 * while `enabled` is false (a previously-chosen template kept for next
 * time); the UI should treat that as "off" either way.
 */
export interface WatermarkDefaultsDto {
  enabled: boolean
  defaultTemplateId: string | null
  defaultTemplateLabel: string | null
}

/** Mirrors WatermarkDefaultsRequest. templateId is required when enabled is true. */
export interface WatermarkDefaultsRequestDto {
  enabled: boolean
  templateId: string | null
}

/** This tenant's download-watermarking policy (`GET /api/v1/watermark-settings`). */
export async function fetchWatermarkDefaults(): Promise<WatermarkDefaultsDto> {
  return apiFetchJson(SETTINGS, undefined, "Could not load the download-watermarking settings.")
}

/** Saves the policy (`PUT /api/v1/watermark-settings`). */
export async function updateWatermarkDefaults(
  request: WatermarkDefaultsRequestDto
): Promise<WatermarkDefaultsDto> {
  return apiFetchJson(
    SETTINGS,
    { method: "PUT", headers: JSON_HEADERS, body: JSON.stringify(request) },
    "Could not save the download-watermarking settings."
  )
}

/**
 * The tenant's templates plus the built-in ones.
 *
 * Doubles as the feature-availability probe (see `useWatermarkAvailability`):
 * a 404 means the endpoints aren't mapped at all because
 * `dms.watermark.enabled` is false, and a 503 WATERMARK_UNAVAILABLE means
 * the server can't render text. Both are left to the caller to interpret -
 * this just doesn't swallow them.
 */
export async function fetchWatermarkTemplates(
  includeInactive = false
): Promise<WatermarkTemplateDto[]> {
  const params = includeInactive ? "?includeInactive=true" : ""
  return apiFetchJson(
    `${TEMPLATES}${params}`,
    undefined,
    "Could not load watermark templates."
  )
}

export async function createWatermarkTemplate(
  request: WatermarkTemplateRequestDto
): Promise<WatermarkTemplateDto> {
  return apiFetchJson(
    TEMPLATES,
    { method: "POST", headers: JSON_HEADERS, body: JSON.stringify(request) },
    "Could not create the template."
  )
}

/** PUT is a whole-template replace: a style field left out is cleared back to the system default. */
export async function updateWatermarkTemplate(
  templateId: string,
  request: WatermarkTemplateRequestDto
): Promise<WatermarkTemplateDto> {
  return apiFetchJson(
    `${TEMPLATES}/${templateId}`,
    { method: "PUT", headers: JSON_HEADERS, body: JSON.stringify(request) },
    "Could not save the template."
  )
}

/** A soft delete - copies already made from it keep their own record of what they say. */
export async function deleteWatermarkTemplate(templateId: string): Promise<void> {
  await apiFetch(
    `${TEMPLATES}/${templateId}`,
    { method: "DELETE" },
    "Could not delete the template."
  )
}

/**
 * A sample watermark as a PNG, with placeholders filled from recognisably
 * fake values rather than the caller's own details.
 *
 * `signal` is threaded through so a keystroke can cancel the previous
 * preview - without it the dialog would race several in flight and show
 * whichever happened to land last, which on a slow connection is routinely
 * the stale one.
 */
export async function previewWatermark(
  request: WatermarkPreviewRequestDto,
  signal?: AbortSignal
): Promise<Blob> {
  const response = await apiFetch(
    `${TEMPLATES}/preview`,
    { method: "POST", headers: JSON_HEADERS, body: JSON.stringify(request), signal },
    "Could not render the preview."
  )
  return response.blob()
}

/**
 * Asks for a watermarked copy. 202 with the job; the copy is produced in
 * the background.
 *
 * `idempotencyKey` is generated once per dialog open and reused on retry,
 * so a double-click or a retry after a network blip returns the SAME job
 * instead of producing a second copy of somebody's document.
 */
export async function requestWatermark(
  documentId: string,
  request: WatermarkRequestDto,
  idempotencyKey: string
): Promise<WatermarkJobDto> {
  return apiFetchJson(
    `${DOCUMENTS}/${documentId}/watermark`,
    {
      method: "POST",
      headers: { ...JSON_HEADERS, "Idempotency-Key": idempotencyKey },
      body: JSON.stringify(request),
    },
    "Could not start the watermark."
  )
}

export async function fetchWatermarkJob(jobId: string): Promise<WatermarkJobDto> {
  return apiFetchJson(`${JOBS}/${jobId}`, undefined, "Could not load the watermark job.")
}

/** The caller's own jobs (`GET /api/v1/watermark-jobs`). `page` is 0-indexed on the wire. */
export async function fetchMyWatermarkJobs(
  status: WatermarkJobStatus | undefined,
  page: number,
  size: number
): Promise<WatermarkJobPageDto> {
  const params = new URLSearchParams()
  if (status) params.set("status", status)
  params.set("page", String(page))
  params.set("size", String(size))
  params.set("sort", "createdAt,desc")
  return apiFetchJson(
    `${JOBS}?${params.toString()}`,
    undefined,
    "Could not load your watermarked copies."
  )
}

/**
 * The finished copy, in the document's own format. Reuses the exports'
 * filename parsing so a watermarked .docx saves as "report-watermarked.docx"
 * and not as a blob.
 */
export async function downloadWatermark(jobId: string): Promise<DownloadedFile> {
  const response = await apiFetch(
    `${JOBS}/${jobId}/result`,
    undefined,
    "Could not download the watermarked copy."
  )
  const blob = await response.blob()
  const header = response.headers.get("Content-Disposition") ?? ""
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(header)
  return {
    blob,
    filename: match ? decodeURIComponent(match[1]) : "watermarked-copy",
  }
}
