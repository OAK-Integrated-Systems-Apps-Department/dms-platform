"use client"

import { TrendChart, type TrendPoint } from "@/components/dashboard/trend-chart"
import {
  formatMetric,
  type MonitoringRange,
  type MonitoringSeries,
} from "@/lib/monitoring/api"

/**
 * Over a day or less the time of day is what locates a point; over a week
 * the day is. Showing both everywhere would collide at this width.
 */
function axisLabel(epochSeconds: number, range: MonitoringRange): string {
  const at = new Date(epochSeconds * 1000)
  return range === "7d"
    ? at.toLocaleDateString(undefined, { day: "numeric", month: "short" })
    : at.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" })
}

/** The full stamp, for the tooltip, where there is room for it. */
function pointLabel(epochSeconds: number): string {
  const at = new Date(epochSeconds * 1000)
  return `${at.toLocaleDateString(undefined, { day: "numeric", month: "short" })} ${at.toLocaleTimeString(
    undefined,
    { hour: "2-digit", minute: "2-digit" }
  )}`
}

/**
 * One metric over time, in a titled card.
 *
 * One series per chart, deliberately. These measures are on wildly different
 * scales - a request rate in the tens, a heap in the hundreds of millions -
 * and putting two on one plot needs a second y-axis, which lets whoever drew
 * it decide which line looks higher.
 */
export function MetricChart({
  series,
  range,
  subtitle,
}: {
  series: MonitoringSeries | undefined
  range: MonitoringRange
  subtitle: string
}) {
  const points: TrendPoint[] =
    series?.points
      // A gap in a scrape is a null. Dropping those is right for a line:
      // drawing through them would invent readings that were never taken.
      .filter((point) => point.value !== null)
      .map((point) => ({
        key: String(point.at),
        label: axisLabel(point.at, range),
        value: point.value as number,
        valueLabel: `${pointLabel(point.at)} · ${
          formatMetric(point.value, series.unit) ?? "—"
        }`,
      })) ?? []

  return (
    <div className="rounded-lg border border-border bg-card p-4">
      <h2 className="text-[14px] font-semibold">{series?.label ?? "Metric"}</h2>
      <p className="mb-3 text-[12px] text-muted-foreground">{subtitle}</p>
      <TrendChart
        points={points}
        ariaLabel={`${series?.label ?? "Metric"} over the last ${range}`}
        emptyText="No readings in this window."
      />
    </div>
  )
}
