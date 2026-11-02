import { API_BASE_URL } from "@/lib/api-config"
import {
  apiFetch,
  apiFetchJson,
  apiUpload,
  type UploadTransfer,
} from "@/lib/api-fetch"
import type { FolderAccessEntryDto, FolderAccessLevel } from "@/lib/folders/api"
import type { PageDto } from "@/lib/workflow/api"

/** Mirrors backend/dms-core's SelectableDocumentTypeResponse - what an upload picker may show. */
export interface SelectableDocumentTypeDto {
  documentTypeId: string
  typeCode: string
  typeName: string
}

/** Mirrors backend/dms-core's ScanResultStatus. */
export type ScanStatus =
  | "pending"
  | "clean"
  | "infected"
  | "failed"
  | "unscannable"
  | "quarantined"
  | "released_override"

/**
 * Mirrors backend/dms-core's IngestionTicketResponse. An upload is only a
 * document once its malware scan passes: 201 = clean and created,
 * 202 = still being scanned (it appears once clean).
 */
export interface IngestionTicketDto {
  scanResultId: string
  status: ScanStatus
  documentId: string | null
  versionId: string | null
  originalFilename: string
  sizeBytes: number | null
  threatName: string | null
  message: string | null
  retrying: boolean
  submittedAt: string
  completedAt: string | null
}

/** Where an upload's scan has got to - polled while it's still pending (202). */
export async function fetchIngestionTicket(
  scanResultId: string
): Promise<IngestionTicketDto> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/ingestion-tickets/${scanResultId}`,
    undefined,
    "Could not check the virus scan."
  )
}

/** Active document types the caller may upload as (needs only docm.documents.create). */
export async function fetchSelectableDocumentTypes(): Promise<
  SelectableDocumentTypeDto[]
> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/document-types/selectable`,
    undefined,
    "Could not load document types."
  )
}

/**
 * Files of any size can be uploaded - the virus scanner checks the ones over
 * 2 GB in parts. Only an organisation's own scan engine limit can refuse one
 * (413), and the server's message then says what that limit is; this is the
 * fallback if it doesn't.
 */
export const TOO_LARGE_MESSAGE =
  "This file is larger than your organisation's upload limit, so it can't be uploaded."

/**
 * One file through `POST /api/v1/documents` (multipart). Throws ApiError on
 * 413 (too large), 415 (type not allowed), 422 (blocked by the malware scan)
 * and any other failure - `status` tells them apart.
 */
export async function uploadDocument(
  file: File,
  documentTypeId: string,
  title: string,
  folderId?: string | null,
  /** Checked for duplicates and found none: refuse (409) if the same file arrived meanwhile. */
  refuseDuplicates = false,
  /**
   * Schema field keys OCR should extract, from the Scan Documents field
   * picker. Omitted for an ordinary upload - the same endpoint either way, so
   * there is one path into the scan gate rather than two to keep in step.
   * OCR starts only after the file comes back clean.
   */
  requestedFields?: string[],
  /** Progress and cancellation - see apiUpload. */
  transfer?: UploadTransfer,
  /** The existing document the user chose "Keep both" against - the backend links the new copy to it as "Duplicate of". */
  duplicateOfDocumentId?: string
): Promise<IngestionTicketDto> {
  const form = new FormData()
  form.append("file", file)
  // No Content-Type header: the browser must set the multipart boundary itself.
  const params = new URLSearchParams({ documentTypeId, title })
  if (folderId) params.set("folderId", folderId)
  if (refuseDuplicates) params.set("refuseDuplicates", "true")
  if (duplicateOfDocumentId) params.set("duplicateOfDocumentId", duplicateOfDocumentId)
  if (requestedFields && requestedFields.length > 0) {
    params.set("requestedFields", JSON.stringify(requestedFields))
  }
  return apiUpload<IngestionTicketDto>(
    `${API_BASE_URL}/api/v1/documents?${params.toString()}`,
    form,
    transfer,
    "Upload failed."
  )
}

/** Mirrors DocumentListItem - one row of the document browser. */
export interface DocumentListItemDto {
  documentId: string
  title: string
  documentTypeId: string
  documentTypeName: string | null
  folderId: string | null
  fileName: string | null
  sizeBytes: number | null
  mimeType: string | null
  versionNumber: number | null
  ownerUserId: string
  createdAt: string
  /** Has its own access list (V54) instead of following its folder. */
  restricted: boolean
  /** What the caller may do with it. */
  myAccess: FolderAccessLevel
}

/** Mirrors DocumentAccessResponse - who can open a document. */
export interface DocumentAccessDto {
  documentId: string
  /** inherit = follows its folder; restricted = its own list. */
  mode: "inherit" | "restricted"
  effectiveMode: "everyone" | "restricted"
  folderId: string | null
  inheritedFromFolderId: string | null
  inheritedFromName: string | null
  entries: FolderAccessEntryDto[]
  myAccess: FolderAccessLevel
  canManage: boolean
}

export async function fetchDocumentAccess(
  documentId: string
): Promise<DocumentAccessDto> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/documents/${documentId}/access`,
    undefined,
    "Could not load who can open this document."
  )
}

export async function saveDocumentAccess(
  documentId: string,
  mode: "inherit" | "restricted",
  entries: Pick<
    FolderAccessEntryDto,
    "principalType" | "principalId" | "level"
  >[]
): Promise<DocumentAccessDto> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/documents/${documentId}/access`,
    {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ mode, entries }),
    },
    "Could not save who can open this document."
  )
}

export interface DocumentPageDto {
  content: DocumentListItemDto[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  last: boolean
}

/**
 * `GET /api/v1/documents`. `folderId` = that folder; null = documents not in
 * any folder; `all` = every document (with `q`, a title search).
 * `documentTypeId` narrows to one type and `excludeDocumentId` leaves one
 * document out - what the workflow document picker uses. `page` is 0-indexed.
 */
export async function fetchDocuments(params: {
  folderId: string | null
  all?: boolean
  q?: string
  documentTypeId?: string
  /** Left out of the results - e.g. the document under approval when picking supporting documents. */
  excludeDocumentId?: string
  /** Relationship filters: a type code, and/or the document the results are related to (direction: which side the results are on). */
  relationshipType?: string
  relatedTo?: string
  direction?: "source" | "target" | "any"
  /** Find by owner: documents owned by this user and/or department (access filtering still applies). */
  ownerId?: string
  ownerDepartmentId?: string
  page: number
  size: number
}): Promise<DocumentPageDto> {
  const search = new URLSearchParams({
    page: String(params.page),
    size: String(params.size),
    sort: "createdAt,desc",
  })
  if (params.folderId) search.set("folderId", params.folderId)
  if (params.all) search.set("all", "true")
  if (params.q?.trim()) search.set("q", params.q.trim())
  if (params.documentTypeId) search.set("documentTypeId", params.documentTypeId)
  if (params.excludeDocumentId)
    search.set("excludeDocumentId", params.excludeDocumentId)
  if (params.relationshipType) search.set("relationshipType", params.relationshipType)
  if (params.relatedTo) search.set("relatedTo", params.relatedTo)
  if (params.direction) search.set("direction", params.direction)
  if (params.ownerId) search.set("ownerId", params.ownerId)
  if (params.ownerDepartmentId) search.set("ownerDepartmentId", params.ownerDepartmentId)
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/documents?${search.toString()}`,
    undefined,
    "Could not load documents."
  )
}

/** Documents shared with the caller by name or role (restricted documents they don't own), newest first. */
export async function fetchSharedWithMe(params: {
  q?: string
  page: number
  size: number
}): Promise<DocumentPageDto> {
  const search = new URLSearchParams({
    page: String(params.page),
    size: String(params.size),
    sort: "createdAt,desc",
  })
  if (params.q?.trim()) search.set("q", params.q.trim())
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/documents/shared-with-me?${search.toString()}`,
    undefined,
    "Could not load what's shared with you."
  )
}

/** Soft-deletes a document (`DELETE /api/v1/documents/{id}`). */
export async function deleteDocument(documentId: string): Promise<void> {
  await apiFetch(
    `${API_BASE_URL}/api/v1/documents/${documentId}`,
    { method: "DELETE" },
    "Could not delete the document."
  )
}

/** The file for the in-app preview - audited as viewed, not downloaded. */
export async function previewDocumentFile(
  documentId: string
): Promise<{ blob: Blob; contentType: string }> {
  const response = await apiFetch(
    `${API_BASE_URL}/api/v1/documents/${documentId}/content?inline=true`,
    undefined,
    "Could not open the document."
  )
  const blob = await response.blob()
  return {
    blob,
    contentType: (response.headers.get("Content-Type") ?? blob.type ?? "")
      .split(";")[0]
      .trim()
      .toLowerCase(),
  }
}

/**
 * Office files the browser can't show (Word .doc, PowerPoint, OpenDocument,
 * RTF, Visio) as a PDF - converted on the server the first time, then kept.
 */
export async function previewConvertedPdf(documentId: string): Promise<Blob> {
  const response = await apiFetch(
    `${API_BASE_URL}/api/v1/documents/${documentId}/preview.pdf`,
    undefined,
    "Could not prepare the preview."
  )
  return response.blob()
}

/** One older version's file, for the in-app preview - audited as viewed, not downloaded. */
export async function previewVersionFile(
  documentId: string,
  versionId: string
): Promise<Blob> {
  const response = await apiFetch(
    `${API_BASE_URL}/api/v1/documents/${documentId}/versions/${versionId}/content?inline=true`,
    undefined,
    "Could not open that version."
  )
  return response.blob()
}

/** An older version of an Office file as a PDF - converted on the server the first time. */
export async function previewVersionPdf(
  documentId: string,
  versionId: string
): Promise<Blob> {
  const response = await apiFetch(
    `${API_BASE_URL}/api/v1/documents/${documentId}/versions/${versionId}/preview.pdf`,
    undefined,
    "Could not prepare the preview."
  )
  return response.blob()
}

/** Mirrors backend/dms-core's DocumentRenditionService.SlideDeck - a presentation shown slide by slide. */
export interface SlideDeckDto {
  versionId: string
  slides: number
  /** The first slide's shape, in points - only the ratio matters. */
  width: number
  height: number
}

function slidesBase(documentId: string, versionId?: string): string {
  return versionId
    ? `${API_BASE_URL}/api/v1/documents/${documentId}/versions/${versionId}/preview/slides`
    : `${API_BASE_URL}/api/v1/documents/${documentId}/preview/slides`
}

/** Opens a presentation (.ppt/.pptx/.odp) as slides - converted on first use, audited as viewed. */
export async function openSlides(
  documentId: string,
  versionId?: string
): Promise<SlideDeckDto> {
  return apiFetchJson<SlideDeckDto>(
    slidesBase(documentId, versionId),
    undefined,
    "Could not prepare the slides."
  )
}

/** One slide (numbered from 1) as a JPEG about `width` pixels wide. */
export async function slideImage(
  documentId: string,
  versionId: string | undefined,
  slide: number,
  width: number
): Promise<Blob> {
  const response = await apiFetch(
    `${slidesBase(documentId, versionId)}/${slide}?width=${width}`,
    undefined,
    "Could not show this slide."
  )
  return response.blob()
}

/** `POST /api/v1/documents/move` - folderId null takes the documents out of any folder. */
export async function moveDocuments(
  documentIds: string[],
  folderId: string | null
): Promise<{ moved: number }> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/documents/move`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ documentIds, folderId }),
    },
    "Could not move the documents."
  )
}

/** Mirrors DocumentVersionService.VersionItem - one row of a document's history. */
export interface DocumentVersionDto {
  versionId: string
  versionNumber: number
  /** "1.2" */
  versionLabel: string
  originalFilename: string
  mimeType: string
  sizeBytes: number
  versionComment: string | null
  /** initial_upload, replacement_upload, rollback, ... */
  createdReason: string
  scanStatus: string
  servable: boolean
  createdAt: string
  createdBy: string | null
  createdByName: string | null
  /** the version that opens and downloads */
  current: boolean
  /** SHA-256 of its content */
  fileHash: string
}

/** `GET /api/v1/documents/{id}/versions` - newest first. */
export async function fetchDocumentVersions(
  documentId: string
): Promise<DocumentVersionDto[]> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/documents/${documentId}/versions`,
    undefined,
    "Could not load the version history."
  )
}

/** `GET /api/v1/documents/{id}/versions/{versionId}/content` - one version's file. */
export async function downloadDocumentVersion(
  documentId: string,
  versionId: string
): Promise<{ blob: Blob; filename: string }> {
  const response = await apiFetch(
    `${API_BASE_URL}/api/v1/documents/${documentId}/versions/${versionId}/content`,
    undefined,
    "Could not download that version."
  )
  const blob = await response.blob()
  const header = response.headers.get("Content-Disposition") ?? ""
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(header)
  return { blob, filename: match ? decodeURIComponent(match[1]) : "document" }
}

/**
 * `POST /api/v1/documents/{id}/versions` - a new version, scanned first; it
 * becomes the current version once clean (201) or when the scan finishes (202).
 */
export async function uploadDocumentVersion(
  documentId: string,
  file: File,
  comment?: string,
  transfer?: UploadTransfer
): Promise<IngestionTicketDto> {
  const form = new FormData()
  form.append("file", file)
  const params = new URLSearchParams()
  if (comment?.trim()) params.set("comment", comment.trim())
  return apiUpload<IngestionTicketDto>(
    `${API_BASE_URL}/api/v1/documents/${documentId}/versions?${params.toString()}`,
    form,
    transfer,
    "Could not upload the new version."
  )
}

/** `POST .../versions/{versionId}/restore` - adds a new version with that version's content. */
export async function restoreDocumentVersion(
  documentId: string,
  versionId: string
): Promise<IngestionTicketDto> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/documents/${documentId}/versions/${versionId}/restore`,
    { method: "POST" },
    "Could not restore that version."
  )
}

/** `GET /api/v1/documents/{id}/content` - the current version's file. */
export async function downloadDocumentFile(
  documentId: string
): Promise<{ blob: Blob; filename: string }> {
  const response = await apiFetch(
    `${API_BASE_URL}/api/v1/documents/${documentId}/content`,
    undefined,
    "Could not download the document."
  )
  const blob = await response.blob()
  const header = response.headers.get("Content-Disposition") ?? ""
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(header)
  return { blob, filename: match ? decodeURIComponent(match[1]) : "document" }
}

// ----------------------------------------------------------------- OCR
// Re-applied from the OCR branch. Its own uploadDocument is gone - uploads
// go through the scan gate above, which now carries the field selection.
// Everything here reads a document that already exists.


/** A document type's required/optional field values, keyed by field_key --
 * mirrors dms-core's `Map<String, Object>` shape: a string for a
 * single-value field, an array for a multi_select field. */
export type MetadataValues = Record<string, string | string[]>


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

/**
 * Documents whose review has already been saved - the counterpart to the
 * queue. Same row shape, so the same table renders both.
 */
export async function fetchSavedReviews(page: number, size: number): Promise<ReviewQueuePage> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/documents/saved-reviews?page=${page}&size=${size}`,
    undefined,
    "Could not load saved reviews."
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

/** Mirrors backend/dms-core's DocumentResponse - a document's metadata (no content). */
export interface DocumentDto {
  documentId: string
  tenantId: string
  documentTypeId: string
  /** Embedded by the backend; null when the type has since been deleted. */
  documentTypeName: string | null
  title: string
  currentVersionId: string | null
  latestVersionId: string | null
  publishedVersionId: string | null
  ownerUserId: string | null
  status: string
  createdAt: string
  updatedAt: string | null
  /** The current-or-latest version's file, for the preview/download on the detail page. Null if it has no version yet. */
  fileName: string | null
  mimeType: string | null
  sizeBytes: number | null
  versionNumber: number | null
  /** Set (1.4) once a workflow with a valid signature approved - null means not locked. */
  signatureLockedAt: string | null
  signatureLockedVersionId: string | null
}

/** Mirrors backend/dms-core's DocumentSummaryResponse - one row of the document list/picker. */
export interface DocumentSummaryDto {
  documentId: string
  title: string
  documentTypeName: string | null
  latestVersionId: string | null
  createdAt: string
  createdByName: string | null
}

export interface DocumentSearchOptions {
  documentTypeId?: string
  /** Left out of the results - e.g. the document under approval when picking supporting documents. */
  excludeDocumentId?: string
}

/** `GET /api/v1/documents` - the tenant's documents, newest first; search matches the title. Needs docm.documents.view. `page` is 0-indexed. */
export async function searchDocuments(
  search: string,
  options: DocumentSearchOptions = {},
  page = 0,
  size = 20
): Promise<PageDto<DocumentSummaryDto>> {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  if (search.trim()) params.set("search", search.trim())
  if (options.documentTypeId) params.set("documentTypeId", options.documentTypeId)
  if (options.excludeDocumentId) params.set("excludeDocumentId", options.excludeDocumentId)
  return apiFetchJson(`${API_BASE_URL}/api/v1/documents?${params.toString()}`, undefined, "Could not load documents.")
}

/** `GET /api/v1/documents/{id}` - needs docm.documents.view. */
export async function fetchDocument(documentId: string): Promise<DocumentDto> {
  return apiFetchJson(`${API_BASE_URL}/api/v1/documents/${documentId}`, undefined, "Could not load this document.")
}
