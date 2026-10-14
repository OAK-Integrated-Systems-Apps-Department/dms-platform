import { API_BASE_URL } from "@/lib/api-config"
import {
  apiFetch,
  apiFetchJson,
  apiUpload,
  type UploadTransfer,
} from "@/lib/api-fetch"
import type { FolderAccessEntryDto, FolderAccessLevel } from "@/lib/folders/api"

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
  /** Progress and cancellation - see apiUpload. */
  transfer?: UploadTransfer
): Promise<IngestionTicketDto> {
  const form = new FormData()
  form.append("file", file)
  // No Content-Type header: the browser must set the multipart boundary itself.
  const params = new URLSearchParams({ documentTypeId, title })
  if (folderId) params.set("folderId", folderId)
  if (refuseDuplicates) params.set("refuseDuplicates", "true")
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
}

/** `GET /api/v1/documents/{id}` - needs docm.documents.view. */
export async function fetchDocument(documentId: string): Promise<DocumentDto> {
  return apiFetchJson(`${API_BASE_URL}/api/v1/documents/${documentId}`, undefined, "Could not load this document.")
}