import type {
  LifecycleStatus,
  MetaFilter,
  MetaOp,
  SearchFilters,
  SearchSort,
} from "./types"

export const PAGE_SIZE = 20

export const EMPTY_FILTERS: SearchFilters = {
  q: "",
  type: "",
  department: "",
  owner: "",
  createdFrom: "",
  createdTo: "",
  modifiedFrom: "",
  modifiedTo: "",
  statuses: ["active"],
  folderId: "",
  includeSubfolders: false,
  meta: [],
  sort: "relevance",
  dir: "desc",
}

const STATES: LifecycleStatus[] = ["active", "inactive", "archived"]
const SORTS: SearchSort[] = ["relevance", "title", "created", "modified"]
const OPS: MetaOp[] = ["eq", "gte", "lte", "contains"]

/** "fieldId:op:value" - the value may itself contain colons. */
function parseMeta(raw: string): MetaFilter | null {
  const first = raw.indexOf(":")
  const second = first < 0 ? -1 : raw.indexOf(":", first + 1)
  if (second < 0) return null
  const op = raw.slice(first + 1, second) as MetaOp
  if (!OPS.includes(op)) return null
  return { fieldId: raw.slice(0, first), op, value: raw.slice(second + 1) }
}

export function metaToString(m: MetaFilter) {
  return `${m.fieldId}:${m.op}:${m.value}`
}

/** The filters in a URL. Anything unrecognised falls back to its default, so a stale or hand-edited link still works. */
export function filtersFromParams(params: URLSearchParams): SearchFilters {
  const statuses = (params.get("status") ?? "")
    .split(",")
    .map((s) => s.trim().toLowerCase())
    .flatMap((s) => (s === "all" ? STATES : [s]))
    .filter((s): s is LifecycleStatus => STATES.includes(s as LifecycleStatus))
  const sort = params.get("sort") as SearchSort | null
  return {
    q: params.get("q") ?? "",
    type: params.get("type") ?? "",
    department: params.get("department") ?? "",
    owner: params.get("owner") ?? "",
    createdFrom: params.get("createdFrom") ?? "",
    createdTo: params.get("createdTo") ?? "",
    modifiedFrom: params.get("modifiedFrom") ?? "",
    modifiedTo: params.get("modifiedTo") ?? "",
    statuses: statuses.length > 0 ? Array.from(new Set(statuses)) : ["active"],
    folderId: params.get("folderId") ?? "",
    includeSubfolders: params.get("includeSubfolders") === "true",
    meta: params
      .getAll("meta")
      .map(parseMeta)
      .filter((m): m is MetaFilter => m !== null),
    sort: sort && SORTS.includes(sort) ? sort : "relevance",
    dir: params.get("dir") === "asc" ? "asc" : "desc",
  }
}

/** The URL for these filters: defaults are left out so the address stays short and shareable. */
export function paramsFromFilters(f: SearchFilters): URLSearchParams {
  const p = new URLSearchParams()
  const set = (key: string, value: string) => {
    if (value.trim()) p.set(key, value.trim())
  }
  set("q", f.q)
  set("type", f.type)
  set("department", f.department)
  set("owner", f.owner)
  set("createdFrom", f.createdFrom)
  set("createdTo", f.createdTo)
  set("modifiedFrom", f.modifiedFrom)
  set("modifiedTo", f.modifiedTo)
  if (!(f.statuses.length === 1 && f.statuses[0] === "active")) {
    p.set(
      "status",
      f.statuses.length === STATES.length ? "all" : f.statuses.join(",")
    )
  }
  set("folderId", f.folderId)
  if (f.folderId && f.includeSubfolders) p.set("includeSubfolders", "true")
  for (const m of f.meta) if (m.fieldId && m.value.trim()) p.append("meta", metaToString({ ...m, value: m.value.trim() }))
  if (f.sort !== "relevance") p.set("sort", f.sort)
  if (f.dir !== "desc") p.set("dir", f.dir)
  return p
}

/** The API request: the same parameters plus the cursor and page size. */
export function apiParams(f: SearchFilters, cursor: string | null): URLSearchParams {
  const p = paramsFromFilters(f)
  // the API's own defaults differ from the URL's for sort direction on relevance; always send what is shown
  p.set("sort", f.sort)
  p.set("dir", f.dir)
  p.set("size", String(PAGE_SIZE))
  if (cursor) p.set("cursor", cursor)
  return p
}

/** How many criteria besides the keywords are set (the badge on the filter button). */
export function activeFilterCount(f: SearchFilters): number {
  let n = 0
  for (const v of [f.type, f.department, f.owner, f.createdFrom, f.createdTo, f.modifiedFrom, f.modifiedTo, f.folderId]) {
    if (v) n++
  }
  n += f.meta.filter((m) => m.fieldId && m.value.trim()).length
  if (!(f.statuses.length === 1 && f.statuses[0] === "active")) n++
  return n
}

/** A stable key for "the same search": when it changes, paging starts again from the first page. */
export function searchSignature(f: SearchFilters): string {
  return paramsFromFilters(f).toString()
}

/** The operators that make sense for a metadata field type. */
export function opsFor(dataType: string): MetaOp[] {
  switch (dataType) {
    case "number":
    case "decimal":
    case "date":
    case "datetime":
      return ["eq", "gte", "lte"]
    case "boolean":
    case "dropdown":
    case "multi_select":
    case "user_ref":
      return ["eq"]
    default:
      return ["contains", "eq"]
  }
}

export const OP_LABELS: Record<MetaOp, string> = {
  eq: "is",
  gte: "is at least",
  lte: "is at most",
  contains: "contains",
}

/** URL parameters that are not part of "the same search": a preview panel that is open, and the preset the page was filled from. */
export const PREVIEW_PARAM = "preview"
export const PRESET_PARAM = "preset"

export interface PageExtras {
  preview: string
  preset: string
}

export function extrasFromParams(params: URLSearchParams): PageExtras {
  return { preview: params.get(PREVIEW_PARAM) ?? "", preset: params.get(PRESET_PARAM) ?? "" }
}

/** The page's query string: the filters, plus whichever extras are set. Extras never affect paging or the search signature. */
export function queryString(filters: SearchFilters, extras: Partial<PageExtras> = {}): string {
  const p = paramsFromFilters(filters)
  if (extras.preset) p.set(PRESET_PARAM, extras.preset)
  if (extras.preview) p.set(PREVIEW_PARAM, extras.preview)
  return p.toString()
}
