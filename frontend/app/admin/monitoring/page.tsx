"use client"

import { useEffect, useState } from "react"
import { ExternalLink, RefreshCw } from "lucide-react"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { Button, buttonVariants } from "@/components/ui/button"
import { LoadingState } from "@/components/ui/loading-state"
import { cn } from "@/lib/utils"
import { GRAFANA_BASE_URL, GRAFANA_DASHBOARD_UID } from "@/lib/api-config"
import { useIsPlatformAdmin } from "@/lib/auth/use-is-platform-admin"
import { useIsTenantAdmin } from "@/lib/auth/use-is-tenant-admin"
import { usePageTitle } from "@/lib/use-page-title"

/** How long to wait for Grafana before assuming it isn't running. */
const LOAD_TIMEOUT_MS = 15_000

const DASHBOARD_URL = `${GRAFANA_BASE_URL}/d/${GRAFANA_DASHBOARD_UID}/`

const RANGES = [
  { label: "1h", value: "now-1h" },
  { label: "6h", value: "now-6h" },
  { label: "24h", value: "now-24h" },
  { label: "7d", value: "now-7d" },
]

/**
 * The dashboard, framed. `kiosk` drops Grafana's own chrome (nav, search,
 * profile) so what shows is only the panels; `theme=light` matches the DMS
 * rather than Grafana's default dark.
 */
function dashboardUrl(range: string) {
  const params = new URLSearchParams({
    kiosk: "",
    theme: "light",
    from: range,
    to: "now",
    refresh: "30s",
  })
  // URLSearchParams renders a valueless key as "kiosk=", which Grafana reads
  // as kiosk mode all the same.
  return `${DASHBOARD_URL}?${params}`
}

/**
 * The iframe and its own loading state. Separate, and given a `key` by the
 * page, so changing the range or pressing Refresh REMOUNTS it: that resets
 * "loading"/"timed out" by construction, instead of an effect writing state
 * back on every change.
 */
function GrafanaFrame({ range }: { range: string }) {
  const [loaded, setLoaded] = useState(false)
  const [timedOut, setTimedOut] = useState(false)

  useEffect(() => {
    const timer = setTimeout(() => setTimedOut(true), LOAD_TIMEOUT_MS)
    return () => clearTimeout(timer)
  }, [])

  return (
    <div className="relative h-[calc(100vh-240px)] min-h-[620px] overflow-hidden rounded-xl border border-border bg-card">
      {!loaded && !timedOut && (
        <div className="absolute inset-0 grid place-items-center">
          <LoadingState message="Loading the monitoring dashboard…" />
        </div>
      )}
      {timedOut && !loaded && (
        <div className="absolute inset-0 grid place-items-center px-6">
          <div className="max-w-[480px] text-center">
            <p className="mb-1.5 text-[14px] font-semibold text-foreground">
              The monitoring dashboard isn&apos;t responding
            </p>
            <p className="text-[12.5px] leading-[1.6] text-muted-foreground">
              Grafana may not be running. Start it with{" "}
              <code className="rounded bg-muted px-1 py-0.5 font-mono text-[11.5px]">
                docker compose up -d grafana prometheus
              </code>{" "}
              in <span className="font-mono">infra/local</span>, then refresh.
              Monitoring is separate from the DMS itself — nothing else is
              affected while it is down.
            </p>
          </div>
        </div>
      )}
      <iframe
        src={dashboardUrl(range)}
        title="OAK DMS system monitoring dashboard"
        // Only says the frame navigated: a Grafana that answered with an
        // error page fires this too, which is why the hint above stays
        // reachable rather than being replaced on load.
        onLoad={() => setLoaded(true)}
        className="size-full border-0"
        // Grafana needs its own scripts and same-origin storage; it is a
        // first-party service on this host, not third-party content.
        sandbox="allow-scripts allow-same-origin allow-popups allow-forms"
        referrerPolicy="same-origin"
      />
    </div>
  )
}

/**
 * Platform admins only: live health and resource usage of the DMS stack -
 * CPU, memory, disk, API latency, database, containers, queues and document
 * processing.
 *
 * The panels are Grafana's, embedded rather than rebuilt: the metrics,
 * dashboard and alert rules all live in infra/monitoring as code, so this
 * page stays a thin frame around them and a new panel needs no frontend
 * change. Grafana is same-origin in production (nginx proxies /grafana/),
 * which is what lets it be framed at all.
 */
export default function SystemMonitoringPage() {
  usePageTitle("System Monitoring")
  // Either kind of administrator, matching the sidebar's `adminOnly` gate.
  // The backend reports them separately - /me/platform-roles carries
  // PLATFORM_ADMIN, the per-user lookup carries TENANT_ADMIN - so both are
  // read and either one is enough.
  const { isPlatformAdmin, isPending: platformPending } = useIsPlatformAdmin()
  const { isTenantAdmin, isPending: tenantPending } = useIsTenantAdmin()
  const isAdmin = isPlatformAdmin || isTenantAdmin
  // Both must settle before deciding: showing "you can't see this" while the
  // second lookup is still in flight would flash a denial at a real admin.
  const isPending = platformPending || tenantPending
  const [range, setRange] = useState("now-6h")
  // Changing this remounts GrafanaFrame, which is the only way to reload a
  // cross-document iframe from here.
  const [reloadKey, setReloadKey] = useState(0)

  return (
    <DashboardShell initialActiveLabel="System Monitoring">
      <AdminBreadcrumb
        className="mb-3"
        segments={[
          { label: "Dashboard", href: "/" },
          { label: "System Monitoring" },
        ]}
      />
      <div className="mb-5 flex flex-wrap items-end justify-between gap-3">
        <div className="max-w-[760px]">
          <h1 className="mb-1.5 font-heading text-[26px] font-semibold tracking-[-0.01em]">
            System Monitoring
          </h1>
          <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
            Live health and resource use of the DMS: the server, the backend,
            the database, queues and document processing. The cards along the
            top say whether everything is healthy; the sections below them say
            what is causing a problem.
          </p>
        </div>
        {isAdmin && (
          <div className="flex items-center gap-1.5">
            <div
              role="radiogroup"
              aria-label="Time range"
              className="flex items-center gap-1 rounded-md border border-border bg-card p-0.5"
            >
              {RANGES.map((option) => (
                <button
                  key={option.value}
                  type="button"
                  role="radio"
                  aria-checked={range === option.value}
                  onClick={() => setRange(option.value)}
                  className={cn(
                    "rounded px-2.5 py-1 text-[12px] font-medium",
                    range === option.value
                      ? "bg-primary text-primary-foreground"
                      : "text-muted-foreground hover:bg-muted hover:text-foreground"
                  )}
                >
                  {option.label}
                </button>
              ))}
            </div>
            <Button
              size="sm"
              variant="outline"
              onClick={() => setReloadKey((n) => n + 1)}
            >
              <RefreshCw size={14} aria-hidden="true" />
              Refresh
            </Button>
            <a
              href={DASHBOARD_URL}
              target="_blank"
              rel="noreferrer noopener"
              className={cn(buttonVariants({ variant: "outline", size: "sm" }))}
            >
              <ExternalLink size={14} aria-hidden="true" />
              Open in Grafana
            </a>
          </div>
        )}
      </div>

      {isPending ? (
        <LoadingState message="Checking your access…" />
      ) : !isAdmin ? (
        <p className="text-[13px] text-muted-foreground">
          Only administrators can see system monitoring.
        </p>
      ) : (
        <GrafanaFrame key={`${range}-${reloadKey}`} range={range} />
      )}
    </DashboardShell>
  )
}
