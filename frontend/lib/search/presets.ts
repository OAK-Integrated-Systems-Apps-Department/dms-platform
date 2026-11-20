import { EMPTY_FILTERS } from "./params"
import type {
  LifecycleStatus,
  MetaFilter,
  SearchFilters,
  SearchNoticeDto,
  SearchResponseDto,
  SearchSort,
} from "./types"

/** The stored shape of a preset's criteria (schema version 1). Mirrors the backend's PresetCriteria. */
export interface PresetCriteria {
  schemaVersion?: number
  q?: string | null
  documentTypeId?: string | null
  departmentId?: string | null
  ownerId?: string | null
  createdFrom?: string | null
  createdTo?: string | null
  modifiedFrom?: string | null
  modifiedTo?: string | null
  statuses?: LifecycleStatus[] | null
  folderId?: string | null
  includeSubfolders?: boolean | null
  meta?: MetaFilter[] | null
  sort?: SearchSort | null
  ascending?: boolean | null
}

export interface PresetDto {
  presetId: string
  name: string
  criteria: PresetCriteria
  schemaVersion: number
  createdAt: string
  updatedAt: string
}

export interface PresetListItemDto {
  presetId: string
  name: string
  summary: string
  updatedAt: string
  runnable: boolean
}

export interface PresetListDto {
  items: PresetListItemDto[]
  count: number
  limit: number
}

export interface PresetRunDto {
  preset: { presetId: string; name: string }
  criteria: PresetCriteria
  warnings: SearchNoticeDto[]
  result: SearchResponseDto
}

/** Whether a date value from a URL/input can be sent as an instant; the filter panel stores whatever the pickers give. */
const orNull = (v: string) => (v.trim() ? v.trim() : null)

/** The page's filters as a preset's criteria. Only what is set is sent; `size` and `cursor` are never part of it. */
export function criteriaFromFilters(f: SearchFilters): PresetCriteria {
  const c: PresetCriteria = { schemaVersion: 1 }
  if (orNull(f.q)) c.q = f.q.trim()
  if (orNull(f.type)) c.documentTypeId = f.type.trim()
  if (orNull(f.department)) c.departmentId = f.department.trim()
  if (orNull(f.owner)) c.ownerId = f.owner.trim()
  if (orNull(f.createdFrom)) c.createdFrom = f.createdFrom.trim()
  if (orNull(f.createdTo)) c.createdTo = f.createdTo.trim()
  if (orNull(f.modifiedFrom)) c.modifiedFrom = f.modifiedFrom.trim()
  if (orNull(f.modifiedTo)) c.modifiedTo = f.modifiedTo.trim()
  if (!(f.statuses.length === 1 && f.statuses[0] === "active")) c.statuses = f.statuses
  if (orNull(f.folderId)) {
    c.folderId = f.folderId.trim()
    if (f.includeSubfolders) c.includeSubfolders = true
  }
  const meta = f.meta.filter((m) => m.fieldId && m.value.trim())
  if (meta.length > 0) c.meta = meta.map((m) => ({ ...m, value: m.value.trim() }))
  if (f.sort !== "relevance") c.sort = f.sort
  if (f.dir === "asc") c.ascending = true
  return c
}

/** A preset's (resolved) criteria as the page's filters, so running one fills the keyword box and the filter panel. */
export function filtersFromCriteria(c: PresetCriteria): SearchFilters {
  return {
    ...EMPTY_FILTERS,
    q: c.q ?? "",
    type: c.documentTypeId ?? "",
    department: c.departmentId ?? "",
    owner: c.ownerId ?? "",
    createdFrom: c.createdFrom ?? "",
    createdTo: c.createdTo ?? "",
    modifiedFrom: c.modifiedFrom ?? "",
    modifiedTo: c.modifiedTo ?? "",
    statuses: c.statuses && c.statuses.length > 0 ? c.statuses : ["active"],
    folderId: c.folderId ?? "",
    includeSubfolders: Boolean(c.folderId && c.includeSubfolders),
    meta: c.meta ?? [],
    sort: c.sort ?? "relevance",
    dir: c.ascending ? "asc" : "desc",
  }
}

/**
 * Whether the criteria hold anything to search for. The same rule the server enforces (PRESET_NO_CRITERIA): sort,
 * direction, the default "active" state and "include subfolders" without a folder do not count.
 */
export function hasPresetCriteria(f: SearchFilters): boolean {
  const c = criteriaFromFilters(f)
  return Boolean(
    c.q ||
      c.documentTypeId ||
      c.departmentId ||
      c.ownerId ||
      c.createdFrom ||
      c.createdTo ||
      c.modifiedFrom ||
      c.modifiedTo ||
      c.statuses ||
      c.folderId ||
      (c.meta && c.meta.length > 0)
  )
}

/** The error codes the presets API answers with. */
export const PRESET_ERRORS = {
  nameExists: "PRESET_NAME_EXISTS",
  limitReached: "PRESET_LIMIT_REACHED",
  noCriteria: "PRESET_NO_CRITERIA",
  invalid: "PRESET_INVALID",
  versionUnsupported: "PRESET_VERSION_UNSUPPORTED",
} as const

/**
 * Criteria in one comparable shape: keys sorted, paging and the schema version ignored, and every default dropped
 * (only "active", "relevance", descending, subfolders without a folder), so a preset read from the server and the
 * criteria built from the page's filters compare equal when they mean the same search.
 */
export function canonicalCriteria(c: Record<string, unknown>): string {
  const skip = new Set(["schemaVersion", "size", "cursor"])
  const clean: Record<string, unknown> = {}
  for (const key of Object.keys(c).sort()) {
    const v = c[key]
    if (skip.has(key) || v === undefined || v === null || v === "" || v === false) continue
    if (key === "statuses") {
      const list = [...(v as string[])].sort()
      if (list.length === 0 || (list.length === 1 && list[0] === "active")) continue
      clean[key] = list
    } else if (key === "sort" && v === "relevance") {
      continue
    } else if (key === "includeSubfolders" && !c.folderId) {
      continue
    } else if (key === "meta") {
      const list = (v as Array<Record<string, unknown>>).map((m) => ({ fieldId: m.fieldId, op: m.op, value: m.value }))
      if (list.length === 0) continue
      clean[key] = list.map((m) => JSON.stringify(m, Object.keys(m).sort())).sort()
    } else {
      clean[key] = v
    }
  }
  return JSON.stringify(clean)
}

export function sameCriteria(a: Record<string, unknown>, b: Record<string, unknown>): boolean {
  return canonicalCriteria(a) === canonicalCriteria(b)
}
