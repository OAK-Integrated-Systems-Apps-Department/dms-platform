"use client"

import { Lock, Mail } from "lucide-react"

export type NotificationTier = "critical" | "standard" | "optional"

/** One notification category, as the preferences and admin settings pages both show it. */
export interface CategoryItem {
  categoryCode: string
  module: string
  tier: NotificationTier
  displayName: string
  description: string | null
  enabled: boolean
  locked: boolean
}

const TIER_NOTE: Record<NotificationTier, string> = {
  critical: "Always on - these security and escalation alerts can't be turned off.",
  standard: "On by default. Turn email off to get these in the inbox only.",
  optional: "Off by default. Turn email on to also be emailed.",
}

const TIER_LABEL: Record<NotificationTier, string> = {
  critical: "Always on",
  standard: "On by default",
  optional: "Off by default",
}

const TIER_CHIP: Record<NotificationTier, string> = {
  critical: "bg-[var(--rust-tint)] text-[var(--rust)]",
  standard: "bg-[var(--primary-tint)] text-primary",
  optional: "bg-[var(--gold-tint)] text-[var(--gold-foreground)]",
}

/**
 * Splits the groups into two columns whose heights (a header row plus one row
 * per notification) are as close as possible, keeping each column in the
 * original order. Group counts are small, so every split is tried.
 */
function splitBalanced<T extends [string, unknown[]]>(entries: T[]): [T[], T[]] {
  const weight = (e: T) => e[1].length + 1
  const total = entries.reduce((n, e) => n + weight(e), 0)
  let best = 0
  let bestDiff = Infinity
  for (let mask = 0; mask < 1 << entries.length; mask++) {
    const left = entries.reduce((n, e, i) => (mask & (1 << i) ? n + weight(e) : n), 0)
    const diff = Math.abs(total - 2 * left)
    if (diff < bestDiff) {
      bestDiff = diff
      best = mask
    }
  }
  return [entries.filter((_, i) => best & (1 << i)), entries.filter((_, i) => !(best & (1 << i)))]
}

/**
 * Categories grouped by module: a summary bar, then one card per module, in
 * two columns of near-equal height from `xl` up (one column below).
 * `unit` finishes the counts, e.g. "7 of 9 {by email}".
 */
export function CategoryGroups({
  items,
  unit,
  disabled,
  onToggle,
}: {
  items: CategoryItem[]
  unit: string
  disabled?: boolean
  onToggle: (item: CategoryItem) => void
}) {
  const on = items.filter((i) => i.enabled).length
  const locked = items.filter((i) => i.locked).length
  const groups = items.reduce<Record<string, CategoryItem[]>>((acc, i) => {
    ;(acc[i.module] ??= []).push(i)
    return acc
  }, {})
  const entries = Object.entries(groups)
  const [left, right] = splitBalanced(entries)

  function renderGroup([module, group]: [string, CategoryItem[]], grow = false) {
    const groupOn = group.filter((i) => i.enabled).length
    return (
      <section
        key={module}
        className={`overflow-hidden rounded-lg border border-border bg-card shadow-[var(--ledger-shadow)] ${grow ? "flex-1" : ""}`}
      >
        <header className="flex items-center justify-between gap-3 border-b border-border bg-[var(--primary-tint)] px-5 py-2.5">
          <h2 className="font-heading text-[14px] font-semibold capitalize">{module}</h2>
          <span className="text-[11.5px] text-muted-foreground">
            {groupOn} of {group.length} {unit}
          </span>
        </header>
        <ul>
          {group.map((item) => (
            <li
              key={item.categoryCode}
              className="flex items-center gap-4 border-b border-border px-5 py-3 last:border-b-0"
            >
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-x-2 text-[13.5px] font-semibold text-foreground">
                  {item.displayName}
                  <span
                    title={TIER_NOTE[item.tier]}
                    className={`border-transparent inline-flex h-6 items-center justify-center gap-1 rounded-btn px-2.5 text-[12px] font-semibold whitespace-nowrap ${TIER_CHIP[item.tier]}`}
                  >
                    {item.locked && <Lock size={10} />}
                    {TIER_LABEL[item.tier]}
                  </span>
                </div>
                {item.description && (
                  <p className="mt-0.5 text-[12px] leading-[1.5] text-muted-foreground">{item.description}</p>
                )}
              </div>
              <span className="w-6 shrink-0 text-right text-[11px] text-[var(--ink-faint)]">
                {item.enabled ? "On" : "Off"}
              </span>
              <button
                type="button"
                role="switch"
                aria-checked={item.enabled}
                aria-label={`Email for ${item.displayName}`}
                disabled={item.locked || disabled}
                onClick={() => onToggle(item)}
                className={`relative h-6 w-11 shrink-0 rounded-full transition-colors ${
                  item.enabled ? "bg-primary" : "bg-[var(--border-strong)]"
                } ${item.locked ? "cursor-not-allowed opacity-60" : ""}`}
              >
                <span
                  className={`absolute top-0.5 size-5 rounded-full bg-white transition-all ${
                    item.enabled ? "left-[22px]" : "left-0.5"
                  }`}
                />
              </button>
            </li>
          ))}
        </ul>
      </section>
    )
  }

  return (
    <>
      <div className="mb-5 flex flex-wrap items-center gap-x-6 gap-y-2 rounded-lg border border-border bg-card px-5 py-3 text-[13px] shadow-[var(--ledger-shadow)]">
        <span className="inline-flex items-center gap-2 font-semibold text-foreground">
          <Mail size={15} className="text-primary" />
          {on} of {items.length} {unit}
        </span>
        <span className="text-muted-foreground">{locked} always on</span>
      </div>

      {/* Narrow screens: one column, in order. */}
      <div className="flex flex-col gap-6 xl:hidden">{entries.map((entry) => renderGroup(entry))}</div>
      {/* Wide screens: two columns of near-equal height, bottoms lined up. */}
      <div className="hidden items-stretch gap-6 xl:grid xl:grid-cols-2">
        {[left, right].map((col, i) => (
          <div key={i} className="flex flex-col gap-6">
            {col.map((entry, j) => renderGroup(entry, j === col.length - 1))}
          </div>
        ))}
      </div>
    </>
  )
}
