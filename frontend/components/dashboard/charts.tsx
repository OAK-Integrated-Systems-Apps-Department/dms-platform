"use client"

import type { CSSProperties, ReactNode } from "react"
import { ArrowDownRight, ArrowUpRight, Minus } from "lucide-react"

import { cn } from "@/lib/utils"

/**
 * Small, honest chart pieces for the dashboard: flat 2D marks (length and
 * position carry the numbers), with depth only in shading and shadows, and
 * motion only as they come in.
 */

export const NUMBER = new Intl.NumberFormat()

/** 1, 2, 2.5 or 5 times a power of ten, at least `value` - so an axis reads 0/5/10/15/20. */
export function niceCeiling(value: number): number {
  if (value <= 4) return 4
  const power = Math.pow(10, Math.floor(Math.log10(value)))
  const step = [1, 2, 2.5, 5, 10].find((s) => s * power >= value) ?? 10
  return step * power
}

export function share(part: number, whole: number): string {
  if (whole <= 0) return "0%"
  const pct = (part / whole) * 100
  return pct > 0 && pct < 1 ? "<1%" : `${Math.round(pct)}%`
}

/** This period against the one before: ▲ 24%, ▼ 8%, "new", or "no change". */
export function DeltaBadge({
  now,
  before,
  className,
}: {
  now: number
  before: number
  className?: string
}) {
  const up = now > before
  const same = now === before
  const label = same
    ? "No change"
    : before === 0
      ? "New"
      : `${Math.round((Math.abs(now - before) / before) * 100)}%`
  const Icon = same ? Minus : up ? ArrowUpRight : ArrowDownRight
  return (
    <span
      title={`${NUMBER.format(now)} this period, ${NUMBER.format(before)} the period before`}
      className={cn(
        "inline-flex items-center gap-0.5 rounded-full px-1.5 py-px text-[11px] font-semibold tabular-nums",
        same
          ? "bg-muted text-muted-foreground"
          : up
            ? "bg-[var(--primary-tint)] text-primary"
            : "bg-[var(--rust-tint)] text-[var(--rust)]",
        className
      )}
    >
      <Icon size={12} />
      {label}
    </span>
  )
}

export interface BarItem {
  key: string
  label: ReactNode
  value: number
  /** Shown at the end of the row instead of the plain count. */
  valueLabel?: string
  color?: string
  /** A secondary line under the label. */
  hint?: string
  onSelect?: () => void
  title?: string
}

/**
 * Ranked horizontal bars - the clearest way to compare a handful of
 * categories: one shared scale, labels you can read, sorted largest first.
 */
export function BarList({
  items,
  total,
  emptyText = "Nothing yet.",
}: {
  items: BarItem[]
  /** What the shares are of; defaults to the sum of the items. */
  total?: number
  emptyText?: string
}) {
  if (items.length === 0) {
    return (
      <p className="py-8 text-center text-[13px] text-muted-foreground">
        {emptyText}
      </p>
    )
  }
  const max = Math.max(1, ...items.map((i) => i.value))
  const whole = total ?? items.reduce((sum, i) => sum + i.value, 0)
  return (
    <ul className="flex flex-col gap-1">
      {items.map((item, i) => {
        const color = item.color ?? "var(--primary)"
        const body = (
          <>
            <span className="flex items-baseline justify-between gap-3 text-[12.5px]">
              <span className="min-w-0 truncate font-medium">{item.label}</span>
              <span className="shrink-0 tabular-nums">
                <span className="font-semibold">
                  {item.valueLabel ?? NUMBER.format(item.value)}
                </span>
                <span className="ml-1.5 inline-block w-9 text-right text-[11px] text-muted-foreground">
                  {share(item.value, whole)}
                </span>
              </span>
            </span>
            <span className="mt-1 block h-2 overflow-hidden rounded-full bg-muted">
              <span
                className="dash-bar-fill block h-full rounded-full"
                style={
                  {
                    "--dash-order": i,
                    width: `${Math.max(2, (item.value / max) * 100)}%`,
                    background: `linear-gradient(90deg, color-mix(in oklab, ${color} 72%, white), ${color})`,
                    boxShadow: `0 1px 4px -1px color-mix(in oklab, ${color} 60%, transparent)`,
                  } as CSSProperties
                }
              />
            </span>
            {item.hint && (
              <span className="mt-0.5 block truncate text-[11px] text-muted-foreground">
                {item.hint}
              </span>
            )}
          </>
        )
        return (
          <li key={item.key}>
            {item.onSelect ? (
              <button
                type="button"
                title={item.title}
                onClick={item.onSelect}
                className="block w-full rounded-lg px-2 py-1.5 text-left transition-colors hover:bg-muted/70"
              >
                {body}
              </button>
            ) : (
              <div title={item.title} className="px-2 py-1.5">
                {body}
              </div>
            )}
          </li>
        )
      })}
    </ul>
  )
}
