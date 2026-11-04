"use client"

import { useState } from "react"

import { cn } from "@/lib/utils"

export interface TrendPoint {
  /** X-axis key, e.g. "2026-03". */
  key: string
  /** What the axis shows for this point, e.g. "Mar". */
  label: string
  value: number
  /** Shown in the tooltip instead of the raw number, e.g. "391 MB". */
  valueLabel?: string
}

const WIDTH = 560
const HEIGHT = 170
const PAD_X = 8
const PAD_TOP = 12
const PAD_BOTTOM = 22

/** Rounds up to a readable axis maximum, so the top gridline is a number people expect. */
function niceMax(value: number): number {
  if (value <= 0) return 1
  const magnitude = 10 ** Math.floor(Math.log10(value))
  const normalised = value / magnitude
  const step =
    normalised <= 1 ? 1 : normalised <= 2 ? 2 : normalised <= 5 ? 5 : 10
  return step * magnitude
}

/**
 * A single-series line over time, with a crosshair and tooltip on hover.
 *
 * One series by design, so there is no legend - the card title names what
 * the line is. Two measures of different scale (documents and bytes, say)
 * get two of these rather than a second y-axis: a dual-axis chart lets the
 * author decide which line looks higher, which is not a decision a chart
 * should be making for its reader.
 *
 * Plain SVG, like every other chart in this dashboard - the project has no
 * charting library, and this needs no layout engine.
 */
export function TrendChart({
  points,
  ariaLabel,
  emptyText = "Nothing yet.",
}: {
  points: TrendPoint[]
  ariaLabel: string
  emptyText?: string
}) {
  const [active, setActive] = useState<number | null>(null)

  if (points.length === 0) {
    return (
      <p className="py-10 text-center text-[13px] text-muted-foreground">
        {emptyText}
      </p>
    )
  }

  const max = niceMax(Math.max(...points.map((p) => p.value)))
  const plotW = WIDTH - PAD_X * 2
  const plotH = HEIGHT - PAD_TOP - PAD_BOTTOM
  // A single point has no span to divide, so it sits at the left edge.
  const stepX = points.length > 1 ? plotW / (points.length - 1) : 0
  const xOf = (i: number) => PAD_X + i * stepX
  const yOf = (v: number) => PAD_TOP + plotH - (v / max) * plotH

  const line = points.map((p, i) => `${xOf(i)},${yOf(p.value)}`).join(" ")
  const area = `${PAD_X},${PAD_TOP + plotH} ${line} ${xOf(points.length - 1)},${PAD_TOP + plotH}`
  const shown = active === null ? null : points[active]

  return (
    <div className="relative">
      <svg
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        className="w-full"
        role="img"
        aria-label={ariaLabel}
        onMouseLeave={() => setActive(null)}
      >
        {/* Recessive gridlines - present for reading values, never competing
            with the data. Only three: top, middle, baseline. */}
        {[0, 0.5, 1].map((fraction) => (
          <line
            key={fraction}
            x1={PAD_X}
            x2={WIDTH - PAD_X}
            y1={PAD_TOP + plotH * fraction}
            y2={PAD_TOP + plotH * fraction}
            stroke="var(--border)"
            strokeWidth={1}
          />
        ))}

        <polygon points={area} fill="var(--chart-1)" opacity={0.1} />
        <polyline
          points={line}
          fill="none"
          stroke="var(--chart-1)"
          strokeWidth={2}
          strokeLinejoin="round"
          strokeLinecap="round"
        />

        {/* Crosshair for the hovered column. */}
        {active !== null && (
          <line
            x1={xOf(active)}
            x2={xOf(active)}
            y1={PAD_TOP}
            y2={PAD_TOP + plotH}
            stroke="var(--chart-1)"
            strokeWidth={1}
            opacity={0.45}
          />
        )}

        {points.map((point, i) => (
          <g key={point.key}>
            {/* A hit target far wider than the mark, so hovering is easy -
                the visible dot alone would be a 4px target. */}
            <rect
              x={xOf(i) - Math.max(stepX, 24) / 2}
              y={PAD_TOP}
              width={Math.max(stepX, 24)}
              height={plotH}
              fill="transparent"
              onMouseEnter={() => setActive(i)}
            />
            <circle
              cx={xOf(i)}
              cy={yOf(point.value)}
              r={active === i ? 5 : 3}
              fill="var(--chart-1)"
              // A ring in the surface colour keeps the marker readable where
              // it overlaps the line or a gridline.
              stroke="var(--card)"
              strokeWidth={2}
            />
          </g>
        ))}

        {/* Axis labels: first, middle and last only - one per month would
            collide at this width and none of them would be read. */}
        {points.map((point, i) =>
          i === 0 ||
          i === points.length - 1 ||
          i === Math.floor((points.length - 1) / 2) ? (
            <text
              key={`${point.key}-label`}
              x={xOf(i)}
              y={HEIGHT - 6}
              textAnchor={
                i === 0 ? "start" : i === points.length - 1 ? "end" : "middle"
              }
              className="fill-muted-foreground text-[10px]"
            >
              {point.label}
            </text>
          ) : null
        )}
      </svg>

      {shown && (
        <div
          className={cn(
            "pointer-events-none absolute top-0 rounded-btn border border-border bg-card px-2.5 py-1.5 shadow-sm",
            // Flips side past the midpoint so the tooltip never leaves the card.
            active !== null && active > points.length / 2 ? "left-2" : "right-2"
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
