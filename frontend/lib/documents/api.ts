import { API_BASE_URL } from "@/lib/api-config"
import { apiFetch, apiFetchJson } from "@/lib/api-fetch"

/** Mirrors com.oak.dms.document.dto.DocumentVersionResponse on the backend. */
export interface DocumentVersionResponse {
  documentId: string
  versionId: string
  title: string
  originalFilename: string
  mimeType: string
  sizeBytes: number
  scanStatus: string
  ocrStatus: string
  createdAt: string
}

/** A document type's required/optional field values, keyed by field_key --
 * mirrors dms-core's `Map<String, Object>` shape: a string for a
 * single-value field, an array for a multi_select field. */
export type MetadataValues = Record<string, string | string[]>

/**
 * Uploads a file as a new document's first version. `documentType` picks
 * which zone/field template (if any) ocr-worker applies -- see
 * backend/workers/ocr-worker's README "Zone / structured-field templates".
 * `metadataValues`, if given, is validated against the document type's
 * mandatory fields server-side before anything is written -- the caller
 * should already have checked this client-side (see
 * lib/document-type-fields/api.ts's `fetchDocumentTypeSchema` +
 * `POST .../validate`) for live per-field feedback, this is the backstop.
 *
 * `requestedFields` is the set of field keys the user chose in the upload
 * form's chip picker -- the server validates it's a subset of this
 * document type's visible schema fields and that every mandatory field is
 * included (a `MetadataValidationFailedException` -- same 400 shape as any
 * other metadata validation error -- otherwise). The document is created
 * with `ocrStatus: "processing"` immediately; OCR/field-extraction then
 * runs in the background -- see `fetchOcrStatus` to poll it.
 *
 * No `Content-Type` header is set on purpose: the browser must set the
 * multipart boundary itself when the body is a `FormData` -- overriding it
 * manually is a classic way to accidentally break the upload.
 */
export async function uploadDocument(
  file: File,
  documentType: string,
  requestedFields: string[],
  metadataValues?: MetadataValues
): Promise<DocumentVersionResponse> {
  const formData = new FormData()
  formData.append("file", file)
  formData.append("documentType", documentType)
  formData.append("requestedFields", JSON.stringify(requestedFields))
  if (metadataValues && Object.keys(metadataValues).length > 0) {
    formData.append("metadataValues", JSON.stringify(metadataValues))
  }

  return apiFetchJson(
    `${API_BASE_URL}/api/v1/documents`,
    { method: "POST", body: formData },
    "Could not upload the document."
  )
}

/** Current saved metadata field values for a document (field_key -> value). */
export async function fetchDocumentMetadata(documentId: string): Promise<MetadataValues> {
  return apiFetchJson(`${API_BASE_URL}/api/v1/documents/${documentId}/metadata`, undefined, "Could not load this document's fields.")
}

/** Replaces the given field keys' saved values; returns the full saved state. */
export async function saveDocumentMetadata(documentId: string, values: MetadataValues): Promise<MetadataValues> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/documents/${documentId}/metadata`,
    { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(values) },
    "Could not save these field values."
  )
}

/**
 * The latest OCR extraction attempt for one requested field key -- see
 * `GET /api/v1/documents/{documentId}/ocr-status`'s response contract.
 * `value` is a plain string for every field except a `multi_select` field
 * (e.g. tags/keywords), which comes back as `string[]`. `source` is `null`
 * when the value has no single spatial location (a label-proximity or
 * free-text-fallback match over concatenated text, same as the old
 * regex-derived fields' `bbox: null`) -- only a real zone-template match
 * carries a page/bbox. A field that wasn't in the upload's/rerun's
 * `requestedFields` never appears in this map at all.
 */
export interface OcrFieldResult {
  value: string | string[] | null
  confidence: number | null
  source: { page: number; bbox: [number, number, number, number] } | null
  status: OcrFieldStatus
}

/**
 * Every status `document_ocr_field_results.status` can hold -- see V45's
 * CHECK constraint, which is the authority on what each one means.
 *
 * `not_extractable` and `defaulted` are NOT variants of the other three:
 * the first means OCR never looked (the field is not something a document
 * states -- V44), the second means the value is real but came from the
 * tenant's default currency rather than off the page. Both get their own
 * badge, because collapsing either into "not found" tells the reviewer
 * something untrue about the document in front of them.
 */
export type OcrFieldStatus =
  | "extracted"
  | "not_found"
  | "low_confidence"
  | "not_extractable"
  | "defaulted"

export interface OcrStatusResponse {
  id: string
  /**
   * "queued" = the job is waiting for an ocr-worker to claim it;
   * "processing" = one actually has. Both are non-terminal (keep polling).
   */
  status: "queued" | "processing" | "completed" | "failed"
  fields: Record<string, OcrFieldResult>
  /**
   * ocr-worker's classified failure code (UNSUPPORTED_FORMAT,
   * ENCRYPTED_PDF, TIMEOUT, ...) -- null unless `status` is "failed".
   * Callers use it to decide whether a retry could ever succeed.
   */
  errorCode: string | null
  /** A short user-facing sentence for `errorCode`; null unless failed. */
  friendlyError: string | null
}

/**
 * Polls a document's OCR/field-extraction status -- upload (and re-run,
 * below) both kick this off in the background rather than blocking on it,
 * so the caller is expected to call this on an interval until `status` is
 * `"completed"` or `"failed"`.
 */
export async function fetchOcrStatus(documentId: string): Promise<OcrStatusResponse> {
  return apiFetchJson(`${API_BASE_URL}/api/v1/documents/${documentId}/ocr-status`, undefined, "Could not check OCR status.")
}

/**
 * Re-runs OCR extraction for just the given field keys on an
 * already-processed version (e.g. the user edited a field and wants a
 * fresh attempt, or wants to add a field they didn't originally select).
 * Fires the same background job `uploadDocument` starts and returns
 * immediately with an ack -- resume polling `fetchOcrStatus` after calling
 * this, the same as right after upload.
 */
export async function rerunOcr(
  versionId: string,
  fields: string[]
): Promise<{ versionId: string; status: string; effectiveFields: string[] }> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/documents/${versionId}/ocr`,
    { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ fields }) },
    "Could not re-run OCR on this document."
  )
}

/**
 * One row of the OCR review queue -- mirrors dms-core's
 * `ReviewQueueItemResponse`.
 *
 * `summary` is the point of the whole screen: a queue that lists
 * documents without saying what is wrong with each one makes the reviewer
 * open every document to find out, which is most of the work the queue
 * exists to save. It is always populated -- a failed document carries the
 * friendly message for its error code, a flagged one carries the rule
 * that fired. `reasons` is the machine-readable form behind it, for
 * filtering and iconography; never render it raw.
 */
export interface ReviewQueueItem {
  documentId: string
  versionId: string
  title: string
  originalFilename: string
  mimeType: string
  createdAt: string
  /** "failed" (OCR never finished) or "completed" (it did, but a rule fired). */
  ocrStatus: string
  requiresReview: boolean
  reasons: string[]
  summary: string
  missingMandatory: string[]
  validationFailures: string[]
  poorQualityPages: number[]
  /** ocr-worker's classified failure code; null unless `ocrStatus` is "failed". */
  errorCode: string | null
}

/** Spring's `Page<ReviewQueueItemResponse>` envelope. */
export interface ReviewQueuePage {
  content: ReviewQueueItem[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

/**
 * Everything in this tenant that failed OCR or came back needing a human,
 * newest first, one page at a time. `page` is 0-indexed here (the
 * backend's own convention) -- the hook above this converts.
 */
export async function fetchReviewQueue(page: number, size: number): Promise<ReviewQueuePage> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/documents/review-queue?page=${page}&size=${size}`,
    undefined,
    "Could not load the review queue."
  )
}

/** One field on the review screen -- mirrors dms-core ReviewFieldResponse. */
export interface ReviewField {
  fieldKey: string
  label: string
  mandatory: boolean
  /** Schema data type; a `multi_select` value is a list, not a string. */
  dataType: string
  value: string | string[] | null
  /** What OCR read before parsing, when that differed from `value`. */
  rawValue: string | null
  confidence: number | null
  status: OcrFieldStatus
  source: { page: number; bbox: [number, number, number, number] } | null
}

/** One invoice line as OCR read it. Read-only on the review screen. */
export interface ReviewLineItem {
  lineNumber: number
  description: string | null
  quantity: string | null
  unitPrice: string | null
  lineTotal: string | null
  confidence: number | null
  sourcePage: number | null
  sourceBbox: [number, number, number, number] | null
  /** The cells exactly as OCR read them, before parsing to numbers. */
  raw: unknown
}

/**
 * Everything the review screen needs, in one call -- mirrors dms-core's
 * `DocumentReviewResponse`. One call rather than four because the screen
 * can render nothing useful until it has all of it, so four round trips
 * would only buy four loading states for one blank page.
 */
export interface DocumentReview {
  documentId: string
  versionId: string
  title: string
  originalFilename: string
  mimeType: string
  ocrStatus: "queued" | "processing" | "completed" | "failed"
  /** How many pages the preview endpoint will serve. */
  pageCount: number
  requiresReview: boolean
  reviewReasons: string[]
  reviewSummary: string
  /**
   * Every field this version asked OCR for, in schema order, INCLUDING
   * the ones it did not find -- a missing mandatory field is the most
   * important thing on the screen and the one with no result row.
   */
  fields: ReviewField[]
  lineItems: ReviewLineItem[]
  errorCode: string | null
  friendlyError: string | null
}

export async function fetchDocumentReview(documentId: string): Promise<DocumentReview> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/documents/${documentId}/review`,
    undefined,
    "Could not load this document for review."
  )
}

/** A rendered page plus the pixel size its highlights are placed against. */
export interface PageImage {
  /** An object URL -- the caller must revoke it when it swaps pages. */
  url: string
  widthPx: number
  heightPx: number
  pageCount: number
}

/**
 * Fetched rather than set as an `<img src>` on purpose. The API is on
 * another origin and auth is a session cookie, so a plain `<img>` would
 * send no credentials and 401 -- and going through `apiFetch` also means
 * an expired access token is refreshed and retried exactly like every
 * other call, instead of the page silently showing a broken image.
 *
 * `page` is 0-indexed, matching `source.page - 1` on a field result.
 */
export async function fetchPageImage(documentId: string, page: number): Promise<PageImage> {
  const response = await apiFetch(
    `${API_BASE_URL}/api/v1/documents/${documentId}/pages/${page}/image`,
    undefined,
    "Could not load that page."
  )
  const blob = await response.blob()
  return {
    url: URL.createObjectURL(blob),
    // The renderer caps total area, so this is NOT dpi x page size and
    // must be read from the response rather than assumed.
    widthPx: Number(response.headers.get("X-Page-Width") ?? 0),
    heightPx: Number(response.headers.get("X-Page-Height") ?? 0),
    pageCount: Number(response.headers.get("X-Page-Count") ?? 1),
  }
}

export interface RegionReading {
  page: number
  bbox: [number, number, number, number]
  text: string
  confidence: number | null
}

/**
 * OCRs a region the user dragged over the page and returns what it says.
 * Stores nothing -- the user decides whether the answer is right, and
 * only that decision is saved (as a correction).
 *
 * Coordinates are normalized (0..1 of page width/height), the same frame
 * every stored bbox lives in, so a selection means the same thing at any
 * zoom or preview resolution.
 */
export async function readRegion(
  documentId: string,
  page: number,
  bbox: [number, number, number, number]
): Promise<RegionReading> {
  const [x0, y0, x1, y1] = bbox
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/documents/${documentId}/read-region`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ page, x0, y0, x1, y1 }),
    },
    "Could not read that part of the page."
  )
}

/** One reviewer decision, as sent when a review is saved. */
export interface ReviewDecision {
  fieldKey: string
  action: "accepted" | "corrected"
  /** The reviewer's value. Ignored by the server for an acceptance. */
  value: string | null
  /** How a correction was produced; null for an acceptance. */
  source: "typed" | "region" | null
}

export interface SaveReviewResult {
  documentId: string
  versionId: string
  accepted: number
  corrected: number
  /** False once every field that needed attention has been settled. */
  requiresReview: boolean
  /** Field keys still outstanding; empty when the review is done. */
  remainingReasons: string[]
}

/**
 * Saves what the reviewer decided. Both their value and OCR's are kept
 * server-side, so the record of how often OCR was wrong survives the fix
 * -- that is the measurement the whole feature exists for.
 *
 * Sent as one batch rather than a call per field: a review is one act of
 * judgement over a document, and a half-saved one would leave it in a
 * state nobody chose.
 */
export async function saveReview(
  documentId: string,
  decisions: ReviewDecision[]
): Promise<SaveReviewResult> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/documents/${documentId}/review`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ decisions }),
    },
    "Could not save your review."
  )
}
