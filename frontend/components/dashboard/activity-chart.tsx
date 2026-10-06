"use client"

import { useState, type CSSProperties } from "react"

import { DeltaBadge, NUMBER, niceCeiling } from "@/components/dashboard/charts"
import {
  DashCard,
  DashSkeleton,
  Pending,
} from "@/components/dashboard/dash-card"
import { useBox, useInView } from "@/components/dashboard/motion"
import type {
  DashboardDay,
  DashboardDto,
  DashboardMonth,
} from "@/lib/dashboard/api"
import { cn } from "@/lib/utils"

const HEIGHT = 240
const PAD = { top: 12, right: 8, bottom: 26, left: 36 }

const SERIES = [
  { key: "views", label: "Views", color: "#2b579a" },
  { key: "downloads", label: "Downloads", color: "var(--gold)" },
  { key: "uploads", label: "Uploads", color: "var(--primary)" },
] as const

type SeriesKey = (typeof SERIES)[number]["key"]

/** The dashboard's period (days, or weeks over 90 days) - or calendar months. */
type View = "period" | "months"

/** The fewest months shown, so a young organisation still sees a trend. */
const MIN_MONTHS = 6

interface Bucket {
  key: string
  /** Under the column. */
  label: string
  /** At the top of the tooltip. */
  title: string
  /** Shows the label even when only some fit (the newest column). */
  always?: boolean
  views: number
  downloads: number
  uploads: number
}

/** Calendar days and months - read as local midnight, not UTC. */
function dateOf(date: string) {
  return new Date(`${date}T00:00:00`)
}

function dayLabel(date: string, weekday = false) {
  return dateOf(date).toLocaleDateString(undefined, {
    day: "numeric",
    month: "short",
    ...(weekday ? { weekday: "short" } : {}),
  })
}

/** A day per column - or, over 90 days, a week per column (counted back from today). */
function periodBuckets(daily: DashboardDay[], days: number): Bucket[] {
  const size = days > 31 ? 7 : 1
  const buckets: Bucket[] = []
  for (let end = daily.length; end > 0; end -= size) {
    const slice = daily.slice(Math.max(0, end - size), end)
    const from = slice[0].date
    const to = slice[slice.length - 1].date
    const today = end === daily.length
    buckets.unshift({
      key: from,
      label: today && size === 1 ? "Today" : dayLabel(from),
      title:
        size === 1
          ? dayLabel(from, true)
          : `${dayLabel(from)} – ${dayLabel(to)}`,
      always: today,
      views: slice.reduce((s, d) => s + d.views, 0),
      downloads: slice.reduce((s, d) => s + d.downloads, 0),
      uploads: slice.reduce((s, d) => s + d.uploads, 0),
    })
  }
  return buckets
}

/**
 * A column per month, from the first with any activity (at least the last
 * six) to this one - so months before anyone used the system aren't a row of
 * empty columns. Only empty months are left out, so the totals still match.
 */
function monthBuckets(months: DashboardMonth[]): Bucket[] {
  const first = months.findIndex((m) => m.views + m.downloads + m.uploads > 0)
  const start = Math.min(
    first === -1 ? months.length : first,
    Math.max(0, months.length - MIN_MONTHS)
  )
  return months.slice(start).map((m, i, shown) => {
    const date = dateOf(m.month)
    const current = i === shown.length - 1
    return {
      key: m.month,
      label: date.toLocaleDateString(undefined, {
        month: "short",
        ...(i === 0 || date.getMonth() === 0 ? { year: "2-digit" } : {}),
      }),
      title: `${date.toLocaleDateString(undefined, { month: "long", year: "numeric" })}${current ? " · to date" : ""}`,
      always: current,
      views: m.views,
      downloads: m.downloads,
      uploads: m.uploads,
    }
  })
}

/**
 * Views, downloads and uploads as stacked columns, with the totals against
 * the span before. By default it follows the dashboard's period (a day each,
 * or a week each over 90 days); "Months" shows calendar months instead, the
 * current one to date, against the same months a year earlier. Each kind can
 * be switched off from its legend entry.
 */
export function ActivityChart({
  data,
  order,
}: {
  data?: DashboardDto
  order: number
}) {
  const [hidden, setHidden] = useState<Set<SeriesKey>>(new Set())
  const [view, setView] = useState<View>("period")
  const usage = view === "months" ? data?.usageYear : data?.usage
  const buckets = !data
    ? []
    : view === "months"
      ? monthBuckets(data.usageMonths)
      : periodBuckets(data.daily, data.days)
  const days = data?.days ?? 30

  return (
    <DashCard
      order={order}
      title="Document engagement"
      subtitle={`${
        data?.usageScope === "organisation"
          ? "Everyone in your organisation"
          : "Your own views, downloads and uploads"
      } · ${
        view === "months"
          ? "by month, against the year before"
          : `last ${days} days, against the ${days} before`
      }`}
      action={
        <div
          role="radiogroup"
          aria-label="Engagement view"
          className="flex shrink-0 rounded-md bg-muted p-0.5"
        >
          {(
            [
              ["period", "Period"],
              ["months", "Months"],
            ] as const
          ).map(([value, label]) => (
            <button
              key={value}
              type="button"
              role="radio"
              aria-checked={view === value}
              onClick={() => setView(value)}
              className={cn(
                "rounded px-2.5 py-1 text-[12px] font-semibold transition-colors",
                view === value
                  ? "bg-card text-foreground shadow-sm"
                  : "text-muted-foreground hover:text-foreground"
              )}
            >
              {label}
            </button>
          ))}
        </div>
      }
    >
      {!data || !usage ? (
        <Pending skeleton={<DashSkeleton className="h-[300px] w-full" />} />
      ) : (
        <>
          <div className="mb-3 flex flex-wrap gap-2">
            {SERIES.map((s) => {
              const now = usage[s.key]
              const before =
                usage[`${s.key}Before` as `${SeriesKey}Before`] ?? 0
              const off = hidden.has(s.key)
              return (
                <button
                  key={s.key}
                  type="button"
                  aria-pressed={!off}
                  onClick={() =>
                    setHidden((current) => {
                      const next = new Set(current)
                      if (next.has(s.key)) next.delete(s.key)
                      else next.add(s.key)
                      return next
                    })
                  }
                  className={cn(
                    "flex min-w-[130px] flex-1 flex-col items-start rounded-xl border px-3 py-2 text-left transition-all",
                    off
                      ? "border-dashed border-border opacity-50"
                      : "border-border bg-muted/40 hover:bg-muted"
                  )}
                >
                  <span className="flex items-center gap-1.5 text-[11.5px] text-muted-foreground">
                    <span
                      className="size-2.5 rounded-[3px]"
                      style={{ background: s.color }}
                    />
                    {s.label}
                  </span>
                  <span className="mt-0.5 flex items-center gap-2">
                    <span className="font-heading text-[20px] leading-tight font-semibold tabular-nums">
                      {NUMBER.format(now)}
                    </span>
                    <DeltaBadge now={now} before={before} />
                  </span>
                </button>
              )
            })}
          </div>
          {/* A new view or period is a new chart - its columns grow in again. */}
          <Columns
            key={view === "months" ? "months" : data.days}
            buckets={buckets}
            hidden={hidden}
            unit={view === "months" ? "month" : data.days > 31 ? "week" : "day"}
          />
        </>
      )}
    </DashCard>
  )
}

function Columns({
  buckets,
  hidden,
  unit,
}: {
  buckets: Bucket[]
  hidden: Set<SeriesKey>
  unit: "day" | "week" | "month"
}) {
  const [frame, box, element] = useBox<HTMLDivElement>()
  // The columns wait at the baseline until the chart is scrolled into view.
  const inView = useInView(element)
  const [hover, setHover] = useState<number | null>(null)
  const shown = SERIES.filter((s) => !hidden.has(s.key))
  const totals = buckets.map((b) => shown.reduce((sum, s) => sum + b[s.key], 0))
  const max = niceCeiling(Math.max(0, ...totals))
  const width = box.width
  const innerWidth = Math.max(0, width - PAD.left - PAD.right)
  const innerHeight = HEIGHT - PAD.top - PAD.bottom
  const band = buckets.length > 0 ? innerWidth / buckets.length : 0
  const column = Math.max(2, Math.min(42, band * 0.68))
  const y = (value: number) =>
    PAD.top + innerHeight - (value / max) * innerHeight
  const ticks = [0, 0.25, 0.5, 0.75, 1].map((f) => Math.round(max * f))
  // Months are labelled when about 44px each fits; days and weeks about six
  // evenly - always the newest.
  const labelEvery =
    unit === "month"
      ? Math.max(1, Math.ceil(44 / Math.max(1, band)))
      : Math.max(1, Math.ceil(buckets.length / 6))
  const hovered = hover !== null ? buckets[hover] : null

  return (
    <div ref={frame} className="dash-cols relative" data-in-view={inView}>
      {totals.every((t) => t === 0) && (
        <p className="absolute inset-x-0 top-[40%] z-10 text-center text-[12.5px] text-muted-foreground">
          {shown.length === 0
            ? "Turn on views, downloads or uploads to see them here."
            : "No views, downloads or uploads in this period yet."}
        </p>
      )}
      {width > 0 && (
        <svg
          width={width}
          height={HEIGHT}
          role="img"
          aria-label={`Views, downloads and uploads per ${unit}`}
          className="block"
          onPointerLeave={() => setHover(null)}
        >
          {ticks.map((tick) => (
            <g key={tick}>
              <line
                x1={PAD.left}
                x2={width - PAD.right}
                y1={y(tick)}
                y2={y(tick)}
                strokeDasharray={tick === 0 ? undefined : "3 5"}
                style={{ stroke: "var(--border)" }}
              />
              <text
                x={PAD.left - 8}
                y={y(tick)}
                dy="0.32em"
                textAnchor="end"
                className="fill-[var(--ink-faint)] text-[10.5px] tabular-nums"
              >
                {NUMBER.format(tick)}
              </text>
            </g>
          ))}

          {buckets.map((bucket, i) => {
            const x = PAD.left + band * i + (band - column) / 2
            let base = PAD.top + innerHeight
            // Narrow daily and weekly columns end at the right edge, so the
            // newest label is right-aligned to fit; months are wide enough.
            const edge = i === buckets.length - 1 && unit !== "month"
            return (
              <g
                key={bucket.key}
                onPointerEnter={() => setHover(i)}
                className="cursor-default"
              >
                {/* The whole band answers the pointer, not just the bars. */}
                <rect
                  x={PAD.left + band * i}
                  y={PAD.top}
                  width={band}
                  height={innerHeight}
                  fill="transparent"
                />
                {hover === i && (
                  <rect
                    x={PAD.left + band * i + 1}
                    y={PAD.top}
                    width={band - 2}
                    height={innerHeight}
                    rx={4}
                    style={{ fill: "var(--muted)" }}
                  />
                )}
                <g
                  className="dash-col"
                  style={{ "--dash-order": i } as CSSProperties}
                >
                  {shown.map((s) => {
                    const value = bucket[s.key]
                    if (value === 0) return null
                    const h = (value / max) * innerHeight
                    base -= h
                    return (
                      <rect
                        key={s.key}
                        x={x}
                        y={base}
                        width={column}
                        height={Math.max(1, h - 1)}
                        rx={Math.min(3, column / 4)}
                        style={{ fill: s.color }}
                      />
                    )
                  })}
                </g>
                {(i % labelEvery === (buckets.length - 1) % labelEvery ||
                  bucket.always) && (
                  <text
                    x={PAD.left + band * i + band / 2}
                    y={HEIGHT - 8}
                    textAnchor={edge ? "end" : "middle"}
                    dx={edge ? band / 2 : 0}
                    className="fill-[var(--ink-faint)] text-[10.5px]"
                  >
                    {bucket.label}
                  </text>
                )}
              </g>
            )
          })}
        </svg>
      )}

      {hovered && hover !== null && (
        <div
          role="status"
          className="pointer-events-none absolute top-0 z-10 min-w-[150px] -translate-x-1/2 rounded-lg border border-border bg-popover px-3 py-2 text-[12px] shadow-[var(--ledger-shadow)]"
          style={{
            left: Math.min(
              Math.max(PAD.left + band * hover + band / 2, 80),
              width - 80
            ),
          }}
        >
          <div className="mb-1 font-semibold">{hovered.title}</div>
          {SERIES.map((s) => (
            <div
              key={s.key}
              className={cn(
                "flex items-center justify-between gap-4",
                hidden.has(s.key) && "opacity-40"
              )}
            >
              <span className="flex items-center gap-1.5 text-muted-foreground">
                <span
                  className="size-2 rounded-[2px]"
                  style={{ background: s.color }}
                />
                {s.label}
              </span>
              <span className="font-semibold tabular-nums">
                {NUMBER.format(hovered[s.key])}
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
