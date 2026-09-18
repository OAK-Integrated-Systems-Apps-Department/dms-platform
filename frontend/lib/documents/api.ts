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

/**
 * Uploads a file as a new document's first version. `documentType` picks
 * which zone/field template (if any) ocr-worker applies -- see
 * backend/workers/ocr-worker's README "Zone / structured-field templates".
 *
 * No `Content-Type` header is set on purpose: the browser must set the
 * multipart boundary itself when the body is a `FormData` -- overriding it
 * manually is a classic way to accidentally break the upload.
 */
export async function uploadDocument(file: File, documentType: string): Promise<DocumentVersionResponse> {
  const formData = new FormData()
  formData.append("file", file)
  formData.append("documentType", documentType)

  return apiFetchJson(
    `${API_BASE_URL}/api/v1/documents`,
    { method: "POST", body: formData },
    "Could not upload the document."
  )
}

/** One cell of a Docling-recognized table -- see ocr-worker README's
 * "Output contract": `bbox` is null when Docling reasoned about a cell
 * structurally without anchoring it to real page geometry. */
export interface OcrTableCell {
  row: number
  col: number
  row_span: number
  col_span: number
  text: string
  bbox: [number, number, number, number] | null
  column_header: boolean
  row_header: boolean
}

export interface OcrTable {
  bbox: [number, number, number, number]
  num_rows: number
  num_cols: number
  cells: OcrTableCell[]
}

export interface OcrPage {
  page_number: number
  method: "native_text" | "paddleocr"
  text: string
  confidence: number
  blocks: { text: string; bbox: [number, number, number, number] | null; confidence: number }[]
  tables: OcrTable[]
}

export interface OcrResult {
  document_version_id: string
  text: string
  pages: OcrPage[]
  fields: Record<string, { value: string; confidence: number; bbox: [number, number, number, number] | null }>
  flags: { low_confidence: boolean; requires_review: boolean }
}

/**
 * Triggers OCR on an already-uploaded version and returns ocr-worker's
 * exact output JSON (see ocr-worker's README "Output contract"), relayed
 * verbatim through dms-core's passthrough route
 * (`com.oak.dms.document.DocumentController#runOcr`) -- this call can take
 * a while (real Docling/PaddleOCR inference), not a quick lookup.
 */
export async function runOcr(versionId: string): Promise<OcrResult> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/documents/${versionId}/ocr`,
    { method: "POST" },
    "Could not run OCR on this document."
  )
}
