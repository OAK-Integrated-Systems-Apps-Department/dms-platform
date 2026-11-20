import type { CatalogDto, CatalogField } from "./api"

/** Mirrors the backend's ReportDefinition, the wire format of a saved or previewed report. */
export interface ReportFilter {
  field: string
  operator: string
  values: string[]
}

export interface ReportSort {
  field: string
  direction: "asc" | "desc"
}

export interface ReportDefinition {
  source: "documents"
  filters: ReportFilter[]
  columns: string[]
  sort: ReportSort[]
  groupBy: string | null
  includeInactive: boolean | null
  timeZone: string | null
}

export function emptyDefinition(catalog?: CatalogDto): ReportDefinition {
  const settings = catalog?.settings
  const defaults = settings?.defaultColumns?.length
    ? settings.defaultColumns
    : (catalog?.fields.filter((f) => f.selectable && f.defaultSelected).map((f) => f.key) ?? [])
  return {
    source: "documents",
    filters: [],
    columns: defaults.length ? defaults : ["title"],
    sort: (settings?.defaultSort ?? []).map((s) => ({ field: s.field, direction: s.direction === "desc" ? "desc" : "asc" })),
    groupBy: null,
    includeInactive: settings?.includeInactiveByDefault ?? false,
    timeZone: null,
  }
}

// ---------------------------------------------------------------- operators

/** Plain-language words for the wire operators. */
export const OPERATOR_LABELS: Record<string, string> = {
  contains: "contains",
  equals: "is exactly",
  starts_with: "starts with",
  is_empty: "is empty",
  is_any_of: "is any of",
  is_none_of: "is none of",
  eq: "is",
  ne: "is not",
  lt: "is less than",
  lte: "is at most",
  gt: "is more than",
  gte: "is at least",
  between: "is between",
  on: "is on",
  before: "is before",
  after: "is after",
  relative: "is",
  is_true: "is yes",
  is_false: "is no",
}

/** How many values an operator takes: 0, 1, 2, or -1 for one or more. */
export const OPERATOR_ARITY: Record<string, number> = {
  contains: 1, equals: 1, starts_with: 1, is_empty: 0, is_any_of: -1, is_none_of: -1, eq: 1, ne: 1, lt: 1, lte: 1,
  gt: 1, gte: 1, between: 2, on: 1, before: 1, after: 1, relative: 1, is_true: 0, is_false: 0,
}

const RELATIVE_LABELS: Record<string, string> = {
  today: "today",
  this_week: "this week",
  last_week: "last week",
  this_month: "this month",
  last_month: "last month",
  this_quarter: "this quarter",
  last_quarter: "last quarter",
  this_year: "this year",
  last_year: "last year",
}

export function relativeLabel(option: string): string {
  const days = /^last_(\d+)_days$/.exec(option)
  if (days) return `in the last ${days[1]} days`
  return RELATIVE_LABELS[option] ?? option.replace(/_/g, " ")
}

export function operatorLabel(operator: string): string {
  return OPERATOR_LABELS[operator] ?? operator.replace(/_/g, " ")
}

// ---------------------------------------------------------------- validation

/** Problems with a definition before it is sent, keyed `columns`, `filters.<i>`, `sort.<i>`, `groupBy`. */
export function validateDefinition(def: ReportDefinition, catalog: CatalogDto): Record<string, string> {
  const problems: Record<string, string> = {}
  const byKey = new Map(catalog.fields.map((f) => [f.key, f]))
  const limits = catalog.settings.limits

  if (def.columns.length === 0) problems.columns = "Choose at least one column."
  else if (def.columns.length > limits.maxColumns) problems.columns = `Choose at most ${limits.maxColumns} columns.`
  for (const key of def.columns) {
    if (!byKey.get(key)?.selectable) {
      problems.columns = "One of the chosen columns is no longer available."
    }
  }
  if (def.filters.length > limits.maxFilters) problems.filters = `Use at most ${limits.maxFilters} filters.`
  def.filters.forEach((f, i) => {
    const field = byKey.get(f.field)
    if (!field?.filterable) {
      problems[`filters.${i}`] = "This field can no longer be filtered."
      return
    }
    const arity = OPERATOR_ARITY[f.operator]
    const given = f.values.filter((v) => v !== "").length
    if (arity === undefined || !field.operators.includes(f.operator)) problems[`filters.${i}`] = "Choose how to compare this field."
    else if (arity === -1 && given < 1) problems[`filters.${i}`] = "Choose at least one value."
    else if (arity >= 0 && given !== arity) problems[`filters.${i}`] = arity === 2 ? "Give both values." : arity === 1 ? "Give a value." : "This takes no value."
  })
  def.sort.forEach((s, i) => {
    if (!byKey.get(s.field)?.sortable) problems[`sort.${i}`] = "This field cannot be sorted."
  })
  if (def.sort.length > limits.maxSortKeys) problems.sort = `Sort by at most ${limits.maxSortKeys} fields.`
  if (def.groupBy && !byKey.get(def.groupBy)?.groupable) problems.groupBy = "This field cannot be grouped by."
  return problems
}

// ---------------------------------------------------------------- the sentence

function valueText(field: CatalogField | undefined, filter: ReportFilter, labels: Record<string, string>): string {
  if (filter.operator === "relative") return relativeLabel(filter.values[0] ?? "")
  const shown = filter.values.map((v) => labels[`${filter.field}:${v}`] ?? v)
  if (filter.operator === "between") return `${shown[0] ?? "…"} and ${shown[1] ?? "…"}`
  if (shown.length > 3) return `${shown.slice(0, 3).join(", ")} and ${shown.length - 3} more`
  return shown.join(field?.type === "choice" || filter.operator === "is_any_of" ? " or " : ", ")
}

/**
 * "Documents where Status is Published and Created in the last 30 days, showing Title, Owner, sorted by Title." The same sentence the
 * server writes into a file's filter summary, for the builder's summary panel. `labels` maps `field:value` to a readable name.
 */
export function summarise(def: ReportDefinition, catalog: CatalogDto | undefined, labels: Record<string, string> = {}): string {
  const byKey = new Map((catalog?.fields ?? []).map((f) => [f.key, f]))
  const name = (key: string) => byKey.get(key)?.label ?? key
  const conditions = def.filters.map((f) => {
    const field = byKey.get(f.field)
    const op = operatorLabel(f.operator)
    const value = valueText(field, f, labels)
    return `${name(f.field)} ${op}${value ? ` ${value}` : ""}`
  })
  const parts = [conditions.length ? `Documents where ${conditions.join(" and ")}` : "All documents you can see"]
  if (!def.includeInactive) parts.push("leaving out inactive and archived ones")
  if (def.columns.length) parts.push(`showing ${def.columns.map(name).join(", ")}`)
  if (def.sort.length) parts.push(`sorted by ${def.sort.map((s) => `${name(s.field)} ${s.direction === "desc" ? "(high to low)" : "(low to high)"}`).join(", ")}`)
  if (def.groupBy) parts.push(`grouped by ${name(def.groupBy)}`)
  return `${parts.join(", ")}.`
}

/** Moves one item; used by the keyboard and drag reordering of columns. */
export function move<T>(items: T[], from: number, to: number): T[] {
  if (from === to || from < 0 || to < 0 || from >= items.length || to >= items.length) return items
  const next = items.slice()
  const [item] = next.splice(from, 1)
  next.splice(to, 0, item)
  return next
}

/** Fields offered in the picker, grouped for display: built-in fields, then the organisation's custom ones. */
export function groupFields(fields: CatalogField[], search: string): { group: string; fields: CatalogField[] }[] {
  const needle = search.trim().toLowerCase()
  const matching = fields.filter((f) => !needle || f.label.toLowerCase().includes(needle) || f.key.toLowerCase().includes(needle))
  const built = matching.filter((f) => !f.metadata)
  const custom = matching.filter((f) => f.metadata)
  return [
    ...(built.length ? [{ group: "Document details", fields: built }] : []),
    ...(custom.length ? [{ group: "Custom fields", fields: custom }] : []),
  ]
}
