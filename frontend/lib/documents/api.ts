import { API_BASE_URL } from "@/lib/api-config"
import { apiFetch, apiFetchJson } from "@/lib/api-fetch"

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

/** Active document types the caller may upload as (needs only docm.documents.create). */
export async function fetchSelectableDocumentTypes(): Promise<SelectableDocumentTypeDto[]> {
  return apiFetchJson(`${API_BASE_URL}/api/v1/document-types/selectable`, undefined, "Could not load document types.")
}

/**
 * One file through `POST /api/v1/documents` (multipart). Throws ApiError on
 * 413 (too large), 415 (type not allowed), 422 (blocked by the malware scan)
 * and any other failure - `status` tells them apart.
 */
export async function uploadDocument(file: File, documentTypeId: string, title: string): Promise<IngestionTicketDto> {
  const form = new FormData()
  form.append("file", file)
  // No Content-Type header: the browser must set the multipart boundary itself.
  const params = new URLSearchParams({ documentTypeId, title })
  const response = await apiFetch(
    `${API_BASE_URL}/api/v1/documents?${params.toString()}`,
    { method: "POST", body: form },
    "Upload failed."
  )
  return response.json() as Promise<IngestionTicketDto>
}

/** Mirrors backend/dms-core's DocumentResponse - a document's metadata (no content). */
export interface DocumentDto {
  documentId: string
  tenantId: string
  documentTypeId: string
  title: string
  currentVersionId: string | null
  latestVersionId: string | null
  publishedVersionId: string | null
  ownerUserId: string | null
  status: string
  createdAt: string
  updatedAt: string | null
}

/** `GET /api/v1/documents/{id}` - needs docm.documents.view. */
export async function fetchDocument(documentId: string): Promise<DocumentDto> {
  return apiFetchJson(`${API_BASE_URL}/api/v1/documents/${documentId}`, undefined, "Could not load this document.")
}
