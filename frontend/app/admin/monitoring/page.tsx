"use client"

import { useState, type CSSProperties } from "react"
import { useQuery } from "@tanstack/react-query"
import {
  Activity,
  Database,
  FileStack,
  Gauge,
  HardDrive,
  HardDriveDownload,
  Hourglass,
  MemoryStick,
  RefreshCw,
  ShieldAlert,
  Signal,
  Users,
  Timer,
} from "lucide-react"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { AlertsCard } from "@/components/monitoring/alerts-card"
import { DependencyHealthCard } from "@/components/monitoring/dependency-health"
import { Gauge as MetricGauge } from "@/components/monitoring/gauge"
import { PanelFrame } from "@/components/monitoring/panel-frame"
import { ServiceStatusList } from "@/components/monitoring/service-status"
import { MetricTile } from "@/components/monitoring/metric-tiles"
import { Button } from "@/components/ui/button"
import { LoadingState } from "@/components/ui/loading-state"
import { useErrorToast } from "@/lib/use-error-toast"
import { useMonitoringAccess } from "@/lib/auth/use-monitoring-access"
import {
  fetchAlerts,
  fetchDependencyHealth,
  fetchMonitoringSeries,
  fetchMonitoringServices,
  fetchMonitoringSnapshot,
  formatMetric,
  MONITORING_RANGES,
  type MonitoringRange,
  type MonitoringReading,
} from "@/lib/monitoring/api"
import { usePageTitle } from "@/lib/use-page-title"
import { cn } from "@/lib/utils"

/** Matches Prometheus's own scrape interval closely enough to look live without hammering it. */
const REFRESH_MS = 30_000

/**
 * Motion for this page, kept in one place.
 *
 * - `sm-reveal`   fades and lifts an element in; `--sm-delay` staggers siblings.
 * - `sm-lift`     the hover treatment for cards: a small rise and a softer shadow.
 * - `sm-skeleton` the shimmer used while the first load is in flight.
 *
 * Everything is switched off under `prefers-reduced-motion`, and only
 * transform/opacity/box-shadow are animated so nothing causes layout shifts.
 */
const MOTION_CSS = `
@keyframes sm-fade-up {
  from { opacity: 0; transform: translateY(8px); }
  to   { opacity: 1; transform: translateY(0); }
}
@keyframes sm-shimmer {
  0%   { background-position: -200% 0; }
  100% { background-position: 200% 0; }
}
.sm-reveal {
  animation: sm-fade-up 0.45s cubic-bezier(0.22, 1, 0.36, 1) both;
  animation-delay: var(--sm-delay, 0ms);
}
.sm-lift {
  position: relative;
  transition: transform 0.22s ease, box-shadow 0.22s ease, filter 0.22s ease;
  will-change: transform;
}
.sm-lift:hover {
  transform: translateY(-4px);
  box-shadow: 0 14px 28px -12px rgb(0 0 0 / 0.35);
  z-index: 1;
}
.sm-lift:active {
  transform: translateY(-1px);
}
/* Built from currentColor so it shows up on any theme, light or dark. */
.sm-skeleton {
  background-color: color-mix(in srgb, currentColor 8%, transparent);
  background-image: linear-gradient(
    90deg,
    transparent 0%,
    color-mix(in srgb, currentColor 14%, transparent) 50%,
    transparent 100%
  );
  background-size: 200% 100%;
  animation: sm-shimmer 1.2s linear infinite;
}
/* Reduced motion: keep the fade and the hover shadow, drop the movement. */
@media (prefers-reduced-motion: reduce) {
  @keyframes sm-fade-up { from { opacity: 0; } to { opacity: 1; } }
  .sm-skeleton { animation: none; }
  .sm-lift, .sm-lift:hover, .sm-lift:active { transform: none; }
}
`

/** Delay for the nth item in a staggered group, capped so long lists do not drag. */
const stagger = (index: number, step = 45): CSSProperties =>
  ({ "--sm-delay": `${Math.min(index * step, 450)}ms` }) as CSSProperties

/**
 * Which panels appear, in which order, under headings of our own.
 *
 * A deliberate subset of the 52 in the dashboard: these are the ones
 * somebody watches, with their own wording. The rest stay in the dashboard
 * for when something is actually being diagnosed, which is a different job
 * from a status page.
 *
 * `id` is the panel's id in infra/monitoring/grafana/dashboards/
 * oak-dms-overview.json. Changing a panel's id there breaks the frame here,
 * which is why ids are never renumbered.
 */
const SECTIONS: {
  title: string
  blurb: string
  panels: { id: number; title: string; subtitle?: string }[]
}[] = [
  {
    title: "Traffic and responsiveness",
    blurb: "What the application is being asked to do, and how quickly it answers.",
    panels: [
      { id: 31, title: "Requests", subtitle: "Per second, split by response status." },
      { id: 32, title: "Error rate", subtitle: "A flat zero is what this should look like." },
      { id: 33, title: "Response time", subtitle: "Median, 95th and 99th percentile." },
      { id: 34, title: "Slowest endpoints", subtitle: "By 95th percentile — where to look first." },
    ],
  },
  {
    title: "Machine and memory",
    blurb: "Whether the host and the backend have room to work.",
    panels: [
      { id: 11, title: "Host CPU", subtitle: "Across the whole machine." },
      { id: 12, title: "Host memory" },
      { id: 13, title: "Heap used against the limit", subtitle: "Sawtooth is normal — that is the garbage collector." },
      { id: 15, title: "Garbage collection", subtitle: "Pause time. Rising means memory pressure." },
    ],
  },
  {
    title: "Storage and database",
    blurb: "The two things that fill up quietly and then stop everything at once.",
    panels: [
      { id: 21, title: "Disk used per mount" },
      { id: 22, title: "Disk activity" },
      { id: 41, title: "Database connections", subtitle: "Against the pool limit." },
      { id: 44, title: "Database size" },
    ],
  },
  {
    title: "Documents, scanning and OCR",
    blurb: "The pipeline every upload goes through.",
    panels: [
      { id: 71, title: "Documents taken in", subtitle: "Per second." },
      { id: 72, title: "Scan outcomes", subtitle: "Clean, infected and failed." },
      { id: 73, title: "Scan duration", subtitle: "95th percentile — large archives dominate this." },
      { id: 74, title: "Scan backlog", subtitle: "Anything stuck here is an upload nobody can open yet." },
      { id: 75, title: "OCR by status" },
      { id: 76, title: "Workflow decisions" },
    ],
  },
  {
    title: "Queues and cache",
    blurb: "RabbitMQ carries the background work; Redis carries what should not be recomputed.",
    // No per-container panels here. cAdvisor cannot read containers under
    // Docker Desktop's overlayfs storage driver - it finds every container
    // and then fails to identify its layer, so every container_* series is
    // empty. An empty chart on a status page is worse than no chart: it
    // reads as "nothing is using CPU". The Services list above answers
    // "which container is up" instead, and does so everywhere.
    panels: [
      { id: 64, title: "Messages waiting", subtitle: "Ready and unacknowledged. A climb means consumers are behind." },
      { id: 62, title: "Cache hit rate", subtitle: "Falling means work is being redone." },
      { id: 61, title: "Cache memory" },
      { id: 63, title: "Cache operations", subtitle: "Per second." },
    ],
  },
]

/** Placeholder shown while the first snapshot loads: same grid as the real page, so nothing jumps when it arrives. */
function MonitoringSkeleton() {
  return (
    <div
      className="flex flex-col gap-5"
      role="status"
      aria-live="polite"
      aria-label="Reading the system metrics"
    >
      <div className="sm-skeleton h-[120px] rounded-lg border border-border" />

      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-6">
        {Array.from({ length: 6 }).map((_, i) => (
          <div
            key={i}
            className="sm-skeleton sm-reveal h-[104px] rounded-lg border border-border"
            style={stagger(i)}
          />
        ))}
      </div>

      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        {Array.from({ length: 7 }).map((_, i) => (
          <div
            key={i}
            className="sm-skeleton sm-reveal h-[92px] rounded-lg border border-border"
            style={stagger(i)}
          />
        ))}
      </div>

      <div>
        <div className="sm-skeleton mb-2 h-4 w-48 rounded" />
        <div className="sm-skeleton mb-3 h-3 w-80 max-w-full rounded" />
        <div className="grid gap-4 lg:grid-cols-2">
          {Array.from({ length: 4 }).map((_, i) => (
            <div
              key={i}
              className="sm-skeleton sm-reveal h-[240px] rounded-lg border border-border"
              style={stagger(i, 70)}
            />
          ))}
        </div>
      </div>

      <span className="sr-only">Reading the system metrics…</span>
    </div>
  )
}

/**
 * Live health of the DMS stack, drawn from Prometheus.
 *
 * Built as a page of the DMS rather than an embedded Grafana dashboard, and
 * the reason is authentication, not looks. Framing Grafana meant running it
 * with anonymous Viewer access so the iframe would render without a second
 * login - which made every metric readable by anyone who could reach
 * Grafana's port, whatever the DMS thought about them. Read through
 * dms-core, the same figures sit behind one authorisation check (platform
 * admin, or the shad.view-system-health-dashboard permission), and neither Prometheus nor Grafana needs
 * to be reachable from a browser at all.
 *
 * Grafana keeps the jobs it is better at - alert rules, ad-hoc exploration,
 * and the long tail of panels - and the link below goes there.
 */
export default function SystemMonitoringPage() {
  usePageTitle("System Monitoring")
  const { canView, isPending } = useMonitoringAccess()
  const [range, setRange] = useState<MonitoringRange>("6h")

  const snapshot = useQuery({
    queryKey: ["monitoring", "snapshot"],
    queryFn: fetchMonitoringSnapshot,
    enabled: canView,
    refetchInterval: REFRESH_MS,
  })
  useErrorToast(snapshot.error, "Could not load the system metrics.")
  // Prometheus itself being unreachable: say so once, as every other error. The tiles then read "no data" rather than 0.
  useErrorToast(
    snapshot.data && !snapshot.data.reachable
      ? `${snapshot.data.error ?? "Prometheus did not answer."} Nothing on this page is current. The DMS itself is unaffected - monitoring runs separately.`
      : null,
    "No metrics are coming through."
  )
  const services = useQuery({
    queryKey: ["monitoring", "services"],
    queryFn: fetchMonitoringServices,
    enabled: canView,
    refetchInterval: REFRESH_MS,
  })
  const alerts = useQuery({
    queryKey: ["monitoring", "alerts"],
    queryFn: fetchAlerts,
    enabled: canView,
    refetchInterval: REFRESH_MS,
  })
  const health = useQuery({
    queryKey: ["monitoring", "health"],
    queryFn: fetchDependencyHealth,
    enabled: canView,
    refetchInterval: REFRESH_MS,
  })
  // Only for the tile sparklines - the charts further down are Grafana's.
  // A tile shows the shape of the window the range control already selects,
  // so there is nothing extra to choose.
  const series = useQuery({
    queryKey: ["monitoring", "series", range],
    queryFn: () => fetchMonitoringSeries(range),
    enabled: canView,
    refetchInterval: REFRESH_MS,
  })

  // The spinner and the footer dot follow every query, not just the snapshot,
  // so the Refresh button never looks idle while something is still in flight.
  // A manual refresh answers in a few milliseconds on a local stack, which is
  // too fast to see, so a click keeps the spinner up for a moment longer.
  const [clicked, setClicked] = useState(false)
  const isRefreshing =
    clicked || snapshot.isFetching || services.isFetching || health.isFetching

  const readings = new Map<string, MonitoringReading>(
    (snapshot.data?.readings ?? []).map((reading) => [reading.id, reading])
  )
  const read = (id: string) => readings.get(id)

  // Whatever the server marked bounded, in catalogue order. The page does
  // not decide which metrics are gauge-able: that needs the PromQL, which
  // lives with the metric.
  const gauges = (snapshot.data?.readings ?? []).filter(
    (reading) => reading.gauge
  )

  const pointsOf = (id: string) =>
    series.data?.series.find((entry) => entry.id === id)?.points

  const targetsUp = read("targets_up")?.value ?? null
  const targetsTotal = read("targets_total")?.value ?? null
  const errorRate = read("error_rate")?.value ?? null
  const scanBacklog = read("scan_backlog")?.value ?? null
  const heapMax = read("heap_max")?.value ?? null

  return (
    <DashboardShell initialActiveLabel="System Monitoring">
      <style>{MOTION_CSS}</style>

      <AdminBreadcrumb
        className="mb-3"
        segments={[
          { label: "Dashboard", href: "/" },
          { label: "System Monitoring" },
        ]}
      />

      <div className="sm-reveal mb-5 flex flex-wrap items-end justify-between gap-3">
        <div className="min-w-0 flex-1">
          <h1 className="mb-1.5 font-heading text-[26px] font-semibold tracking-[-0.01em]">
            System Monitoring
          </h1>
          <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
            Live health and resource use of this installation — the server, the
            backend, the database and document processing. The cards say what
            is true right now; the charts say how it got there.
          </p>
        </div>
        {canView && (
          <div className="flex items-center gap-1.5">
            <div
              role="radiogroup"
              aria-label="Time range"
              className="flex items-center gap-1 rounded-btn border border-border bg-card p-0.5"
            >
              {MONITORING_RANGES.map((option) => (
                <button
                  key={option}
                  type="button"
                  role="radio"
                  aria-checked={range === option}
                  onClick={() => setRange(option)}
                  className={cn(
                    "rounded-btn px-2.5 py-1 text-[12px] font-medium",
                    "transition-[background-color,color,transform] duration-150",
                    "motion-safe:active:scale-95",
                    "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40",
                    range === option
                      ? "bg-primary text-primary-foreground"
                      : "text-muted-foreground hover:bg-muted hover:text-foreground"
                  )}
                >
                  {option}
                </button>
              ))}
            </div>
            <Button
              size="sm"
              variant="outline"
              disabled={isRefreshing}
              aria-busy={isRefreshing}
              className="transition-transform duration-150 motion-safe:active:scale-95"
              onClick={() => {
                setClicked(true)
                window.setTimeout(() => setClicked(false), 900)
                void snapshot.refetch()
                void services.refetch()
                void series.refetch()
                void health.refetch()
                void alerts.refetch()
              }}
            >
              <RefreshCw
                size={14}
                aria-hidden="true"
                className={cn(
                  "transition-transform duration-300",
                  isRefreshing && "animate-spin"
                )}
              />
              {isRefreshing ? "Refreshing" : "Refresh"}
            </Button>
          </div>
        )}
      </div>

      {isPending ? (
        <LoadingState message="Checking your access…" />
      ) : !canView ? (
        <div className="sm-reveal rounded-lg border border-border bg-card px-6 py-16 text-center">
          <p className="text-[14px] font-semibold">
            You don&apos;t have access to system monitoring
          </p>
          <p className="mx-auto mt-1.5 max-w-[420px] text-[12.5px] leading-[1.6] text-muted-foreground">
            These figures cover the whole installation — the host, the
            database and every organisation on it — so it needs its own
            permission. Ask your administrator to give you a role with
            &ldquo;View System Health Dashboard&rdquo;.
          </p>
        </div>
      ) : snapshot.isPending ? (
        <MonitoringSkeleton />
      ) : snapshot.isError ? (
        null
      ) : (
        <div className="flex flex-col gap-5">
          {services.data && (
            <div className="sm-reveal" style={stagger(1)}>
              <ServiceStatusList services={services.data.services} />
            </div>
          )}

          {/* Beside the board above, not instead of it: that one says a
              port is listening, this one says the backend can actually use
              what is behind it. */}
          {health.data && (
            <div className="sm-reveal" style={stagger(2)}>
              <DependencyHealthCard health={health.data} />
            </div>
          )}

          {/* Directly under the dependencies, and not tucked below the
              charts: a firing alert is the most urgent thing this page can
              say, and nothing else delivers it - there is no Alertmanager. */}
          {alerts.data && (
            <div className="sm-reveal" style={stagger(3)}>
              <AlertsCard alerts={alerts.data} />
            </div>
          )}

          {gauges.length > 0 && (
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-6">
              {gauges.map((reading, i) => (
                <div
                  key={reading.id}
                  className="sm-reveal sm-lift rounded-lg"
                  style={stagger(i)}
                >
                  <MetricGauge
                    label={reading.label}
                    value={reading.value}
                    thresholds={{
                      warnAt: reading.warnAt ?? 0.75,
                      criticalAt: reading.criticalAt ?? 0.9,
                    }}
                  />
                </div>
              ))}
            </div>
          )}

          {/* All nine in ONE grid. They were split across two - seven over
              four columns, then two over two - which left the last row a
              different width from everything above it. Three columns divides
              nine exactly, and gives each tile room for its sparkline. */}
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {[
              <MetricTile
                key="targets"
                icon={Signal}
                label="Monitored targets"
                reading={read("targets_up")}
                detail={
                  targetsTotal !== null
                    ? `of ${targetsTotal} configured`
                    : undefined
                }
                tone={
                  targetsUp !== null &&
                  targetsTotal !== null &&
                  targetsUp < targetsTotal
                    ? "bad"
                    : undefined
                }
              />,
              <MetricTile
                key="requests"
                icon={Activity}
                label="Requests"
                reading={read("request_rate")}
                points={pointsOf("request_rate")}
                detail="Averaged over 5 minutes"
              />,
              <MetricTile
                key="errors"
                icon={ShieldAlert}
                label="Server errors"
                reading={read("error_rate")}
                points={pointsOf("error_rate")}
                detail={
                  errorRate !== null && errorRate === 0
                    ? "None in the last 5 minutes"
                    : "Averaged over 5 minutes"
                }
                tone={errorRate !== null && errorRate > 0 ? "bad" : undefined}
              />,
              <MetricTile
                key="latency"
                icon={Timer}
                label="Response time (p95)"
                reading={read("latency_p95")}
                points={pointsOf("latency_p95")}
                detail="95 in 100 are faster than this"
              />,
              <MetricTile
                key="heap"
                icon={MemoryStick}
                label="Heap in use"
                reading={read("heap_used")}
                points={pointsOf("heap_used")}
                detail={
                  heapMax !== null
                    ? `of ${formatMetric(heapMax, "BYTES")} allocated`
                    : undefined
                }
              />,
              <MetricTile
                key="db"
                icon={Database}
                label="Database connections"
                reading={read("db_connections")}
                points={pointsOf("db_connections")}
                detail="In use right now"
              />,
              <MetricTile
                key="backlog"
                icon={Gauge}
                label="Scan backlog"
                reading={read("scan_backlog")}
                points={pointsOf("scan_backlog")}
                detail={
                  scanBacklog !== null && scanBacklog === 0
                    ? "Nothing waiting"
                    : "Waiting on the virus scanner"
                }
                tone={
                  scanBacklog !== null && scanBacklog > 0 ? "warn" : undefined
                }
              />,
              <MetricTile
                key="documents"
                icon={FileStack}
                label="Documents stored"
                reading={read("documents_stored")}
                detail="Across every organisation"
              />,
              <MetricTile
                key="storage"
                icon={HardDrive}
                label="Storage used"
                reading={read("storage_bytes")}
                detail="Live versions only"
              />,
              <MetricTile
                key="disk-free"
                icon={HardDriveDownload}
                label="Disk free"
                reading={read("disk_free")}
                detail="On the fullest filesystem"
              />,
              <MetricTile
                key="uptime"
                icon={Hourglass}
                label="Backend uptime"
                reading={read("backend_uptime")}
                detail="Since its last restart"
              />,
              <MetricTile
                key="consumers"
                icon={Users}
                label="Queue consumers"
                reading={read("rabbit_consumers")}
                detail="Picking up background work"
                // Zero is the failure: work queues up and is never taken,
                // while RabbitMQ itself reports healthy.
                tone={
                  read("rabbit_consumers")?.value === 0 ? "bad" : undefined
                }
              />,
            ].map((tile, i) => (
              <div
                key={tile.key}
                className="sm-reveal sm-lift h-full rounded-lg"
                style={stagger(i)}
              >
                {tile}
              </div>
            ))}
          </div>

          {SECTIONS.map((section) => (
            <section key={section.title} className="sm-reveal">
              <h2 className="mb-0.5 text-[15px] font-semibold">
                {section.title}
              </h2>
              <p className="mb-3 text-[12.5px] text-muted-foreground">
                {section.blurb}
              </p>
              <div className="grid gap-4 lg:grid-cols-2">
                {section.panels.map((panel, i) => (
                  <div
                    key={panel.id}
                    // No sm-lift here, unlike the tiles. A chart is something
                    // you point at to read a value, and a card that rises 4px
                    // the moment the pointer enters it moves the chart out
                    // from under the pointer. It also put the hover shadow on
                    // THIS wrapper, which is as tall as its grid row, while
                    // the card inside was only as tall as its own content -
                    // so beside a taller panel a second, empty card edge
                    // showed below it.
                    className="sm-reveal h-full rounded-lg"
                    style={stagger(i, 60)}
                  >
                    <PanelFrame
                      panelId={panel.id}
                      title={panel.title}
                      subtitle={panel.subtitle}
                      range={range}
                    />
                  </div>
                ))}
              </div>
            </section>
          ))}

          <p className="flex items-center gap-2 text-[12px] text-muted-foreground">
            <span
              aria-hidden="true"
              className="relative flex size-1.5 items-center justify-center"
            >
              {isRefreshing && (
                <span className="absolute inline-flex size-full animate-ping rounded-full bg-primary opacity-60 motion-reduce:animate-none" />
              )}
              <span
                className={cn(
                  "relative inline-flex size-1.5 rounded-full transition-colors duration-300",
                  isRefreshing ? "bg-primary" : "bg-muted-foreground/40"
                )}
              />
            </span>
            {isRefreshing ? "Updating…" : "Refreshes every 30 seconds."}
          </p>
        </div>
      )}
    </DashboardShell>
  )
}