import { API_BASE_URL } from "@/lib/api-config"
import { apiFetch, apiFetchJson, apiFetchVoid } from "@/lib/api-fetch"
import type { DocumentListItemDto } from "@/lib/documents/api"

const BASE = `${API_BASE_URL}/api/v1/bookmarks`

export type BookmarkSortKey = "bookmarkedAt" | "title"
export type SortDirection = "asc" | "desc"

/** Mirrors BookmarkService.BookmarkedDocument. */
export interface BookmarkedDocumentDto {
  /** The same row the document browser shows. */
  document: DocumentListItemDto
  bookmarkedAt: string
  note: string | null
  /** Still readable, but archived - shown flagged rather than removed. */
  archived: boolean
}

/** Mirrors BookmarkService.BookmarksPage. */
export interface BookmarksPageDto {
  total: number
  page: number
  size: number
  /** Bookmarks whose document is in the recycle bin or no longer visible - hidden, not deleted. */
  unavailableCount: number
  items: BookmarkedDocumentDto[]
}

/** `GET /api/v1/bookmarks`. `page` is 0-indexed. */
export async function fetchBookmarks(params: {
  page: number
  size: number
  sort?: BookmarkSortKey
  dir?: SortDirection
  q?: string
}): Promise<BookmarksPageDto> {
  const search = new URLSearchParams({
    page: String(params.page),
    size: String(params.size),
    sort: params.sort ?? "bookmarkedAt",
    dir: params.dir ?? "desc",
  })
  if (params.q?.trim()) search.set("q", params.q.trim())
  return apiFetchJson(
    `${BASE}?${search.toString()}`,
    undefined,
    "Could not load your bookmarks."
  )
}

/**
 * Bookmarks a document, or - if the caller already bookmarked it - updates its note (when one is
 * supplied). Idempotent: `created` is false when it already existed.
 */
export async function addBookmark(
  documentId: string,
  note?: string
): Promise<{ created: boolean }> {
  return apiFetchJson(
    `${BASE}/${documentId}`,
    {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ note: note ?? null }),
    },
    "Could not bookmark the document."
  )
}

/** Updates the note on the caller's own bookmark. 404 if they have none on this document. */
export async function updateBookmarkNote(
  documentId: string,
  note: string | null
): Promise<void> {
  await apiFetchVoid(
    `${BASE}/${documentId}/note`,
    {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ note }),
    },
    "Could not save the note."
  )
}

/** Removes the caller's own bookmark. Always succeeds, whether or not they had one. */
export async function removeBookmark(documentId: string): Promise<void> {
  await apiFetch(
    `${BASE}/${documentId}`,
    { method: "DELETE" },
    "Could not remove the bookmark."
  )
}

/**
 * Which of `documentIds` the caller has bookmarked - up to 200 at a time. Lets a list page render
 * filled bookmark icons in one call without loading the whole bookmarks list.
 */
export async function fetchBookmarkStatus(
  documentIds: string[]
): Promise<Set<string>> {
  if (documentIds.length === 0) return new Set()
  const ids = documentIds.slice(0, 200)
  const params = new URLSearchParams({ ids: ids.join(",") })
  const result: { bookmarkedDocumentIds: string[] } = await apiFetchJson(
    `${BASE}/status?${params.toString()}`,
    undefined,
    "Could not check which documents are bookmarked."
  )
  return new Set(result.bookmarkedDocumentIds)
}
