import { API_BASE_URL } from "@/lib/api-config"
import { apiFetch, apiFetchJson } from "@/lib/api-fetch"

const DOCUMENTS = `${API_BASE_URL}/api/v1/documents`
const PREVIEW = `${API_BASE_URL}/api/v1/preview`
const JSON_HEADERS = { "Content-Type": "application/json" }

/** What a user may do with print or download from a preview. */
export type PreviewMode = "allow" | "watermark" | "block"

/** Mirrors backend/dms-core's PreviewPolicyService.Policy - copying text can only be allowed or blocked. */
export interface PreviewPolicyDto {
  print: PreviewMode
  download: PreviewMode
  copy: "allow" | "block"
}

/** How previews have always worked - and what an organisation has until an administrator changes it. */
export const OPEN_POLICY: PreviewPolicyDto = {
  print: "allow",
  download: "allow",
  copy: "allow",
}

/** True when nothing is restricted: previews are the plain ones. */
export function isOpenPolicy(policy: PreviewPolicyDto): boolean {
  return (
    policy.print === "allow" &&
    policy.download === "allow" &&
    policy.copy === "allow"
  )
}

export async function fetchPreviewPolicy(): Promise<PreviewPolicyDto> {
  return apiFetchJson(
    `${PREVIEW}/policy`,
    undefined,
    "Could not load the preview settings."
  )
}

export async function savePreviewPolicy(
  policy: PreviewPolicyDto
): Promise<PreviewPolicyDto> {
  return apiFetchJson(
    `${PREVIEW}/policy`,
    { method: "PUT", headers: JSON_HEADERS, body: JSON.stringify(policy) },
    "Could not save the preview settings."
  )
}

/** Mirrors DocumentRenditionService.FailureView - one preview that could not be made. */
export interface PreviewFailureDto {
  versionId: string
  documentId: string
  title: string
  fileName: string
  versionNumber: number
  reason: string
  failedAt: string
}

export interface PreviewFailurePageDto {
  content: PreviewFailureDto[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  last: boolean
}

export async function fetchPreviewFailures(
  page: number,
  size: number
): Promise<PreviewFailurePageDto> {
  return apiFetchJson(
    `${PREVIEW}/failures?page=${page}&size=${size}`,
    undefined,
    "Could not load the failed previews."
  )
}

export async function retryPreviewFailure(versionId: string): Promise<void> {
  await apiFetch(
    `${PREVIEW}/failures/${versionId}/retry`,
    { method: "POST" },
    "Could not retry that preview."
  )
}

// ------------------------------------------------------------ pages and thumbnails

/** Mirrors DocumentRenditionService.PageDeck - a file drawn page by page on the server. */
export interface PageDeckDto {
  versionId: string
  /** How many pages can be shown. */
  pages: number
  /** The first page's shape (points) - only the ratio matters. */
  width: number
  height: number
  /** Only the first `pages` pages are shown (a very large file). */
  partial: boolean
  /** How long the whole file is, when known. */
  totalPages: number | null
  /** The viewer's name is burned into every page. */
  watermarked: boolean
}

function pagesBase(documentId: string, versionId?: string): string {
  return versionId
    ? `${DOCUMENTS}/${documentId}/versions/${versionId}/preview/pages`
    : `${DOCUMENTS}/${documentId}/preview/pages`
}

/** Opens a file as pages drawn on the server - audited as viewed. */
export async function openPages(
  documentId: string,
  versionId?: string
): Promise<PageDeckDto> {
  return apiFetchJson(
    pagesBase(documentId, versionId),
    undefined,
    "Could not prepare the preview."
  )
}

/** One page (numbered from 1) as a JPEG about `width` pixels wide. */
export async function pageImage(
  documentId: string,
  versionId: string | undefined,
  page: number,
  width: number
): Promise<Blob> {
  const response = await apiFetch(
    `${pagesBase(documentId, versionId)}/${page}?width=${width}`,
    undefined,
    "Could not show this page."
  )
  return response.blob()
}

/** The first-page picture of a document, or null when there is none (the caller shows an icon). */
export async function fetchThumbnail(
  documentId: string,
  versionId?: string
): Promise<Blob | null> {
  const response = await apiFetch(
    versionId
      ? `${DOCUMENTS}/${documentId}/versions/${versionId}/thumbnail`
      : `${DOCUMENTS}/${documentId}/thumbnail`,
    undefined,
    "Could not load the thumbnail."
  )
  if (response.status === 204) return null
  return response.blob()
}

/**
 * The Download button of a preview, under the organisation's preview policy - a watermarked copy when it says so.
 * The server refuses (403 PREVIEW_DOWNLOAD_BLOCKED) when downloads from a preview are blocked.
 */
export async function downloadFromPreview(
  documentId: string,
  versionId?: string
): Promise<{ blob: Blob; filename: string }> {
  const response = await apiFetch(
    versionId
      ? `${DOCUMENTS}/${documentId}/versions/${versionId}/content?channel=preview`
      : `${DOCUMENTS}/${documentId}/content?channel=preview`,
    undefined,
    "Could not download the document."
  )
  const blob = await response.blob()
  const header = response.headers.get("Content-Disposition") ?? ""
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(header)
  return { blob, filename: match ? decodeURIComponent(match[1]) : "document" }
}

const PAGE_EXTENSIONS = new Set([
  "pdf",
  "png",
  "jpg",
  "jpeg",
  "gif",
  "bmp",
  "tif",
  "tiff",
  "doc",
  "docx",
  "ppt",
  "pptx",
  "odt",
  "odp",
  "odg",
  "rtf",
  "vsd",
  "vsdx",
  "xls",
  "xlsx",
  "ods",
  "txt",
  "csv",
])

/**
 * Whether the server can draw this file as pages (and so protect it with the preview policy) - mirrors
 * DocumentRenditionService.protectable. Anything else can't be previewed while previews are restricted.
 */
export function canDrawAsPages(fileName: string | null): boolean {
  const name = (fileName ?? "").toLowerCase()
  const dot = name.lastIndexOf(".")
  return dot >= 0 && PAGE_EXTENSIONS.has(name.slice(dot + 1))
}
