"use client"

import { useState, type CSSProperties } from "react"

import { share } from "@/components/dashboard/charts"
import { cn } from "@/lib/utils"

export interface DonutSlice {
  key: string
  label: string
  value: number
  color: string
  /** Shown in the legend and the centre instead of the plain value (e.g. "391 MB"). */
  valueLabel?: string
  /** A second, quieter figure in the legend (e.g. "2 files"). */
  detail?: string
}

const SIZE = 140
const STROKE = 22
const RADIUS = (SIZE - STROKE) / 2
const CIRCUMFERENCE = 2 * Math.PI * RADIUS
/** A thin gap between slices, in the units of the circumference. */
const GAP = 2

/** Where each slice starts and how long it is, around the circumference, in order. */
function arcsOf(slices: DonutSlice[], whole: number) {
  const lengths = slices.map((s) =>
    whole > 0 ? (s.value / whole) * CIRCUMFERENCE : 0
  )
  return slices.map((slice, i) => ({
    slice,
    start: lengths.slice(0, i).reduce((sum, l) => sum + l, 0),
    length: lengths[i],
  }))
}

/**
 * A flat donut for a part-to-whole split across a few categories, with its
 * legend. Slices are true to size (no 3D, no minimum), sweep in when shown,
 * and point at a slice - or its legend row - to see it in the centre.
 */
export function DonutChart({
  slices,
  total,
  centerLabel,
  centerValue,
  ariaLabel,
}: {
  slices: DonutSlice[]
  /** What the shares are of; defaults to the sum of the slices. */
  total?: number
  /** Under the big figure in the centre, when nothing is pointed at (e.g. "files"). */
  centerLabel: string
  /** The big figure in the centre, when nothing is pointed at. */
  centerValue: string
  ariaLabel: string
}) {
  const [active, setActive] = useState<string | null>(null)
  const whole = total ?? slices.reduce((sum, s) => sum + s.value, 0)
  const shown = slices.filter((s) => s.value > 0)
  const focused = shown.find((s) => s.key === active)
  // Only leave gaps when there's more than one slice - a single one is a full ring.
  const gap = shown.length > 1 ? GAP : 0

  const arcs = arcsOf(shown, whole)

  return (
    <div className="flex flex-wrap items-center justify-center gap-x-6 gap-y-4">
      <div
        className="relative shrink-0"
        style={{ width: SIZE, height: SIZE }}
        onPointerLeave={() => setActive(null)}
      >
        <svg
          width={SIZE}
          height={SIZE}
          viewBox={`0 0 ${SIZE} ${SIZE}`}
          role="img"
          aria-label={`${ariaLabel}: ${shown
            .map(
              (s) =>
                `${s.label} ${s.valueLabel ?? s.value} (${share(s.value, whole)})`
            )
            .join(", ")}`}
          className="-rotate-90 overflow-visible"
        >
          <circle
            cx={SIZE / 2}
            cy={SIZE / 2}
            r={RADIUS}
            fill="none"
            strokeWidth={STROKE}
            style={{ stroke: "var(--muted)" }}
          />
          {arcs.map(({ slice, start, length }, i) => {
            const visible = Math.max(0, length - gap)
            return (
              <circle
                key={slice.key}
                cx={SIZE / 2}
                cy={SIZE / 2}
                r={RADIUS}
                fill="none"
                strokeWidth={active === slice.key ? STROKE + 6 : STROKE}
                strokeDashoffset={-start}
                onPointerEnter={() => setActive(slice.key)}
                className={cn(
                  "dash-donut-arc cursor-default transition-[stroke-width,opacity] duration-200",
                  active && active !== slice.key && "opacity-35"
                )}
                style={
                  {
                    stroke: slice.color,
                    "--circ": `${CIRCUMFERENCE}`,
                    "--dash-order": i,
                    strokeDasharray: `${visible} ${CIRCUMFERENCE}`,
                  } as CSSProperties
                }
              />
            )
          })}
        </svg>
        {/* Upright, in the hole. */}
        <div
          aria-hidden="true"
          className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center px-6 text-center"
        >
          <span className="font-heading text-[19px] leading-tight font-semibold tabular-nums">
            {focused ? (focused.valueLabel ?? focused.value) : centerValue}
          </span>
          <span className="mt-0.5 line-clamp-2 text-[10.5px] leading-tight text-muted-foreground">
            {focused
              ? `${focused.label} · ${share(focused.value, whole)}`
              : centerLabel}
          </span>
        </div>
      </div>

      <ul className="flex min-w-[170px] flex-1 flex-col gap-1">
        {slices.map((slice) => (
          <li key={slice.key}>
            <button
              type="button"
              onPointerEnter={() => setActive(slice.key)}
              onPointerLeave={() => setActive(null)}
              onFocus={() => setActive(slice.key)}
              onBlur={() => setActive(null)}
              aria-label={`${slice.label}: ${slice.valueLabel ?? slice.value}, ${share(slice.value, whole)}${slice.detail ? `, ${slice.detail}` : ""}`}
              className={cn(
                "grid w-full grid-cols-[auto_1fr_auto_auto] items-center gap-2 rounded-md px-1.5 py-1 text-left text-[12px] transition-colors focus-visible:ring-2 focus-visible:ring-ring/50 focus-visible:outline-none",
                active === slice.key ? "bg-muted" : "hover:bg-muted/60"
              )}
            >
              <span
                aria-hidden="true"
                className="size-2.5 rounded-[3px]"
                style={{ background: slice.color }}
              />
              <span className="min-w-0">
                <span className="block truncate">{slice.label}</span>
                {slice.detail && (
                  <span className="block truncate text-[10.5px] text-muted-foreground">
                    {slice.detail}
                  </span>
                )}
              </span>
              <span className="font-semibold tabular-nums">
                {slice.valueLabel ?? slice.value}
              </span>
              <span className="w-9 text-right text-[11px] text-muted-foreground tabular-nums">
                {share(slice.value, whole)}
              </span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  )
}
