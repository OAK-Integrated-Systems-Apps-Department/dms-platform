"use client"

import { useState } from "react"

import { cn } from "@/lib/utils"

export interface Column {
  key: string
  /** What the axis shows for this column, e.g. "Mar". */
  label: string
  value: number
  /** Shown in the tooltip instead of the raw number. */
  valueLabel?: string
}

/**
 * Discrete buckets over time, as columns.
 *
 * A column rather than a line because each month is a separate count, not a
 * reading taken along a continuum - a line between two months implies values
 * in between that nothing measured. One hue throughout: the columns encode
 * magnitude, which their height already carries, so colouring them per month
 * would invent a category that is not there.
 *
 * Plain HTML, not SVG - a flex row of divs lays out and labels itself, and
 * there is no path to compute.
 */
export function ColumnChart({
  columns,
  ariaLabel,
  emptyText = "Nothing yet.",
}: {
  columns: Column[]
  ariaLabel: string
  emptyText?: string
}) {
  const [active, setActive] = useState<number | null>(null)

  if (columns.length === 0) {
    return (
      <p className="py-8 text-center text-[13px] text-muted-foreground">
        {emptyText}
      </p>
    )
  }

  const max = Math.max(1, ...columns.map((c) => c.value))
  const shown = active === null ? null : columns[active]

  return (
    <div
      className="relative"
      role="img"
      aria-label={ariaLabel}
      onMouseLeave={() => setActive(null)}
    >
      {/* Baseline and midline only - enough to read a height against, quiet
          enough never to compete with the columns. */}
      <div className="relative h-[120px]">
        <div
          aria-hidden="true"
          className="absolute inset-x-0 top-1/2 border-t border-border"
        />
        <div
          aria-hidden="true"
          className="absolute inset-x-0 bottom-0 border-t border-border"
        />
        {/* gap-[2px] is the surface gap the marks need: without it adjacent
            columns touch and read as one wide block. */}
        <div className="absolute inset-0 flex items-end gap-[2px]">
          {columns.map((column, i) => (
            <button
              key={column.key}
              type="button"
              // The hit target is the full column height, not just the bar -
              // a zero month is a 2px mark and would be unhoverable.
              onMouseEnter={() => setActive(i)}
              onFocus={() => setActive(i)}
              onBlur={() => setActive(null)}
              aria-label={`${column.label}: ${column.valueLabel ?? column.value.toLocaleString()}`}
              className="group flex h-full flex-1 cursor-default items-end"
            >
              <span
                className={cn(
                  "block w-full rounded-t-[4px] transition-opacity",
                  active !== null && active !== i && "opacity-45"
                )}
                style={{
                  // A floor of 2px so an empty month is still visibly a month
                  // rather than a hole in the row.
                  height: `${Math.max(2, (column.value / max) * 100)}%`,
                  background: "var(--chart-1)",
                }}
              />
            </button>
          ))}
        </div>
      </div>

      {/* One label per column collides at this width; first, middle and last
          are enough to place any column in time. */}
      <div className="mt-1.5 flex gap-[2px]">
        {columns.map((column, i) => (
          <span
            key={`${column.key}-label`}
            className={cn(
              "flex-1 text-center text-[10px] text-muted-foreground",
              i !== 0 &&
                i !== columns.length - 1 &&
                i !== Math.floor((columns.length - 1) / 2) &&
                "invisible"
            )}
          >
            {column.label}
          </span>
        ))}
      </div>

      {shown && (
        <div
          className={cn(
            "pointer-events-none absolute top-0 rounded-md border border-border bg-card px-2.5 py-1.5 shadow-sm",
            // Flips side past the midpoint so it never leaves the card.
            active !== null && active > columns.length / 2 ? "left-0" : "right-0"
          )}
        >
          <p className="text-[11px] text-muted-foreground">{shown.label}</p>
          <p className="text-[13px] font-semibold tabular-nums">
            {shown.valueLabel ?? shown.value.toLocaleString()}
          </p>
        </div>
      )}
    </div>
  )
}
