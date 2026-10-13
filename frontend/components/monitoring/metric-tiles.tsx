"use client"

import type { LucideIcon } from "lucide-react"

import { cn } from "@/lib/utils"
import {
  formatMetric,
  type MonitoringPoint,
  type MonitoringReading,
} from "@/lib/monitoring/api"

/**
 * Splits "1.39/s" into "1.39" and "/s", "268 MB" into "268" and "MB".
 *
 * The unit is set smaller and lighter than the figure. At a glance a row of
 * tiles should compare by their numbers, and a unit rendered at the same
 * weight competes with the digit beside it - "9 ms" reads as a two-part
 * thing rather than as nine.
 */
function splitUnit(formatted: string): [string, string] {
  const match = formatted.match(/^([\d.,-]+)\s*(.*)$/)
  return match ? [match[1], match[2]] : [formatted, ""]
}

const SPARK_W = 104
const SPARK_H = 30

/**
 * The shape of the last little while, behind the number.
 *
 * No axes, no labels, no hover: a tile answers "what is it now", and the
 * line is there only to say whether now is normal. Anything that invites
 * reading a value off it belongs in the charts further down the page, which
 * have the room to be read properly.
 *
 * Scaled to its own minimum and maximum rather than to zero, because the
 * question is the shape, not the magnitude - the magnitude is printed next
 * to it in full.
 */
function Sparkline({
  points,
  colour,
}: {
  points: MonitoringPoint[]
  colour: string
}) {
  const values = points
    .map((point) => point.value)
    .filter((value): value is number => value !== null && Number.isFinite(value))

  if (values.length < 2) return null

  const min = Math.min(...values)
  const max = Math.max(...values)
  // A flat series has no range to divide by; draw it down the middle rather
  // than at the top or bottom, where it would imply a reading it is not.
  const span = max - min || 1
  const step = SPARK_W / (values.length - 1)
  const y = (value: number) =>
    max === min ? SPARK_H / 2 : SPARK_H - 2 - ((value - min) / span) * (SPARK_H - 4)

  const line = values.map((value, i) => `${i * step},${y(value)}`).join(" ")

  return (
    <svg
      viewBox={`0 0 ${SPARK_W} ${SPARK_H}`}
      className="h-[30px] w-[104px] shrink-0 overflow-visible"
      aria-hidden="true"
      focusable="false"
    >
      <polygon
        points={`0,${SPARK_H} ${line} ${SPARK_W},${SPARK_H}`}
        fill={colour}
        opacity={0.1}
      />
      <polyline
        points={line}
        fill="none"
        stroke={colour}
        strokeWidth={1.75}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <circle
        cx={SPARK_W}
        cy={y(values[values.length - 1])}
        r={2.25}
        fill={colour}
      />
    </svg>
  )
}

/**
 * One figure, with its own "no data" state.
 *
 * A metric with no sample shows an em dash, never 0. The difference matters
 * more here than anywhere else in the app: on a monitoring screen, "0 errors"
 * and "we cannot see the errors" look identical if both render as 0, and one
 * of them means the monitoring is broken.
 */
export function MetricTile({
  icon: Icon,
  label,
  reading,
  detail,
  tone,
  points,
}: {
  icon: LucideIcon
  label: string
  reading?: MonitoringReading
  /** A second, quieter line - a limit, a share, a count it is out of. */
  detail?: string
  /** Set only when the figure itself is the warning. Never used for decoration. */
  tone?: "warn" | "bad"
  /** Recent samples, when this metric has a history worth a line. */
  points?: MonitoringPoint[]
}) {
  const formatted = reading ? formatMetric(reading.value, reading.unit) : null
  const [figure, unit] = formatted ? splitUnit(formatted) : ["—", ""]

  const toneColour =
    tone === "bad"
      ? "var(--status-bad)"
      : tone === "warn"
        ? "var(--status-warn)"
        : undefined

  return (
    <div
      className={cn(
        "flex h-full flex-col rounded-xl border bg-card p-4 transition-colors",
        // The border carries the alarm, not the whole card: a tinted block
        // for every raised figure turns a busy minute into a wall of colour.
        tone === "bad"
          ? "border-[var(--status-bad)]"
          : tone === "warn"
            ? "border-[var(--status-warn)]"
            : "border-border"
      )}
    >
      <div className="mb-3 flex items-center gap-2">
        <span
          className="flex size-7 shrink-0 items-center justify-center rounded-lg bg-muted text-muted-foreground"
          aria-hidden="true"
        >
          <Icon size={15} />
        </span>
        <span className="min-w-0 truncate text-[12.5px] font-medium text-muted-foreground">
          {label}
        </span>
      </div>

      <div className="flex items-end justify-between gap-3">
        <p
          className={cn(
            "leading-none font-semibold tabular-nums",
            formatted === null ? "text-[26px] text-muted-foreground" : "text-[28px]"
          )}
          style={toneColour ? { color: toneColour } : undefined}
        >
          {figure}
          {unit && (
            <span className="ml-1 text-[15px] font-medium text-muted-foreground">
              {unit}
            </span>
          )}
        </p>
        {formatted !== null && points && (
          <Sparkline
            points={points}
            // Status colour only when the figure itself is raised; otherwise
            // a neutral series colour, so a line never looks like a warning
            // just for existing.
            colour={toneColour ?? "var(--chart-1)"}
          />
        )}
      </div>

      <p className="mt-2 text-[11.5px] text-muted-foreground">
        {formatted === null ? "No data" : (detail ?? " ")}
      </p>
    </div>
  )
}
