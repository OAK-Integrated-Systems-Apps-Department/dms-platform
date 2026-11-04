"use client"

import { useState } from "react"

import { cn } from "@/lib/utils"
import type { MonitoringRange } from "@/lib/monitoring/api"

/**
 * The dashboard the panels come from. Provisioned as code in
 * infra/monitoring/grafana/dashboards - which is also where a panel is
 * added or changed, never through a UI.
 */
const DASHBOARD_UID = "oak-dms-overview"
const DASHBOARD_SLUG = "oak-dms-system-monitoring"

/**
 * Same-origin, served by this app's own proxy route. Deliberately not a
 * Grafana host: the browser is never told where Grafana is, and the route
 * is the only thing that can reach it.
 */
const PANEL_BASE = `/monitoring/panels/d-solo/${DASHBOARD_UID}/${DASHBOARD_SLUG}`

const RANGE_FROM: Record<MonitoringRange, string> = {
  "1h": "now-1h",
  "6h": "now-6h",
  "24h": "now-24h",
  "7d": "now-7d",
}

/**
 * One panel, framed.
 *
 * `d-solo` renders a single panel and nothing else - no navigation, no
 * search, no branding, no dashboard title. That is what makes these read as
 * part of the DMS rather than as a window into another product. The heading
 * above each frame is ours, not Grafana's, so the wording stays the
 * product's own.
 */
export function PanelFrame({
  panelId,
  title,
  subtitle,
  range,
  height = 220,
  className,
}: {
  panelId: number
  title: string
  subtitle?: string
  range: MonitoringRange
  height?: number
  className?: string
}) {
  const [failed, setFailed] = useState(false)

  const params = new URLSearchParams({
    panelId: String(panelId),
    from: RANGE_FROM[range],
    to: "now",
    // Grafana's own panel chrome off; the card below supplies the title.
    theme: "light",
    refresh: "30s",
  })

  return (
    <div
      className={cn(
        // h-full + flex column: the card fills its grid cell, and the frame
        // below takes whatever height is left, so panels in one row line up
        // instead of each stopping where its own content ends.
        "flex h-full flex-col rounded-lg border border-border bg-card p-4",
        className
      )}
    >
      <h3 className="text-[14px] font-semibold">{title}</h3>
      {subtitle && (
        <p className="mb-2 text-[12px] text-muted-foreground">{subtitle}</p>
      )}
      {failed ? (
        <p
          className="grid place-items-center text-center text-[12.5px] text-muted-foreground"
          style={{ height }}
        >
          This chart isn&apos;t available right now.
        </p>
      ) : (
        <iframe
          // Remounts when the range changes, so the frame reloads without
          // reaching into another document to tell it to.
          key={range}
          src={`${PANEL_BASE}?${params}`}
          title={title}
          loading="lazy"
          onError={() => setFailed(true)}
          className="w-full flex-1 border-0"
          // A floor, not a fixed height: beside a taller panel this one grows
          // to match rather than stopping short with blank card below it.
          style={{ minHeight: height }}
          // Same-origin by construction - this is our own path. Grafana
          // needs its scripts and its storage to render a panel at all.
          sandbox="allow-scripts allow-same-origin"
          referrerPolicy="same-origin"
        />
      )}
    </div>
  )
}
