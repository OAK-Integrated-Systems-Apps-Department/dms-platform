/** Mirrors backend/dms-core's search API (SearchController, SearchResponse, SearchFilterOptions). */

export type LifecycleStatus = "active" | "inactive" | "archived"
export type SearchSort = "relevance" | "title" | "created" | "modified"
export type MetaOp = "eq" | "gte" | "lte" | "contains"

/** A typed filter on one configurable metadata field. */
export interface MetaFilter {
  fieldId: string
  op: MetaOp
  value: string
}

/**
 * Everything the user can ask for, as plain strings so it round-trips through the URL. Empty string = not set. A new
 * filter (tags, one day) is one more field here, one line in params.ts, and one control in the filter panel.
 */
export interface SearchFilters {
  q: string
  type: string
  department: string
  owner: string
  createdFrom: string
  createdTo: string
  modifiedFrom: string
  modifiedTo: string
  /** Lifecycle states to include. Default ["active"]; inactive/archived need crm.view-archive. */
  statuses: LifecycleStatus[]
  folderId: string
  includeSubfolders: boolean
  meta: MetaFilter[]
  sort: SearchSort
  dir: "asc" | "desc"
}

export interface SearchHighlights {
  title: string | null
  fileName: string | null
  body: string[]
}

export interface SearchItemDto {
  documentId: string
  title: string
  fileName: string | null
  documentTypeId: string | null
  documentTypeName: string | null
  folderId: string | null
  ownerUserId: string | null
  ownerDepartmentId: string | null
  createdAt: string
  updatedAt: string | null
  lifecycleState: LifecycleStatus
  legalHold: boolean
  mimeType: string | null
  /** ready | pending | unavailable: whether the words inside the file are searchable. Null in basic mode. */
  bodyStatus: string | null
  highlights: SearchHighlights
  /** Phase 2: the version the thumbnail belongs to (a new version has a new picture). */
  versionNumber?: number | null
  sizeBytes?: number | null
  /** The existing thumbnail endpoint; null when the caller lacks docm.documents.view (show the file-type icon). */
  thumbnailUrl?: string | null
  /** Pinned for the caller (their own pin, or one whose audience names them). Decided by the server for this page only. */
  pinned?: boolean
  bookmarked?: boolean
}

export interface SearchNoticeDto {
  code: string
  message: string
  details: string[]
}

export interface SearchResponseDto {
  /** "search" (full search) or "basic" (the title-search fallback). */
  mode: "search" | "basic"
  items: SearchItemDto[]
  /** exact=false means "this many or more". */
  total: { value: number; exact: boolean }
  nextCursor: string | null
  notices: SearchNoticeDto[]
  didYouMean: string | null
}

export interface SearchFilterOptionsDto {
  documentTypes: { id: string; name: string }[]
  departments: { id: string; name: string }[]
  owners: { id: string; name: string }[]
  metadataFields: {
    fieldId: string
    key: string
    label: string
    dataType: string
    options: { value: string; label: string }[]
  }[]
}
