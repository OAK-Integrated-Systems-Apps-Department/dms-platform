import type { PolicyScope, RetentionUnit } from "./api"

/** "1 year", "6 years", "30 days" - an amount and unit as an administrator reads them. */
export function formatPeriod(amount: number, unit: RetentionUnit): string {
  const singular = unit.replace(/s$/, "")
  return `${amount} ${amount === 1 ? singular : unit}`
}

/** The backend's own "1 years" style text ("activePeriod") turned into proper English. */
export function formatPeriodText(text: string | null): string {
  if (!text) return "-"
  const [amount, unit] = text.split(" ")
  const n = Number(amount)
  if (!Number.isFinite(n) || !["days", "months", "years"].includes(unit)) return text
  return formatPeriod(n, unit as RetentionUnit)
}

/** "1 year active, then 6 years archived"; "30 days active, no archive period" when there is none. */
export function describeRule(rule: {
  activeAmount: number
  activeUnit: RetentionUnit
  archiveAmount: number
  archiveUnit: RetentionUnit
}): string {
  const active = `${formatPeriod(rule.activeAmount, rule.activeUnit)} active`
  return rule.archiveAmount > 0 ? `${active}, then ${formatPeriod(rule.archiveAmount, rule.archiveUnit)} archived` : `${active}, no archive period`
}

export const SCOPE_LABELS: Record<PolicyScope, string> = {
  organisation: "Whole organisation",
  document_type: "Document type",
  department: "Department",
  folder: "Folder",
}

/** "keeps at most 5 versions, removes those older than 2 years" - what a rule does to old versions, or null when nothing. */
export function describeVersionLimits(rule: {
  maxVersions: number | null
  versionAgeAmount: number | null
  versionAgeUnit: RetentionUnit | null
}): string | null {
  const parts: string[] = []
  if (rule.maxVersions != null) parts.push(`keeps at most ${rule.maxVersions} version${rule.maxVersions === 1 ? "" : "s"}`)
  if (rule.versionAgeAmount != null && rule.versionAgeUnit)
    parts.push(`removes old versions after ${formatPeriod(rule.versionAgeAmount, rule.versionAgeUnit)}`)
  return parts.length ? parts.join(", ") : null
}
