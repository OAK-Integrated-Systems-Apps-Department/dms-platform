import { API_BASE_URL } from "@/lib/api-config"
import { apiFetchJson } from "@/lib/api-fetch"

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
  status: "extracted" | "not_found" | "low_confidence"
}

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
