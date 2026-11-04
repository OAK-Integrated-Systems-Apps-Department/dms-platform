"use client"

import {
  useCallback,
  useEffect,
  useId,
  useRef,
  useState,
  useSyncExternalStore,
} from "react"
import { createPortal } from "react-dom"
import { CircleCheck, CircleX, Radio, X } from "lucide-react"

import { ServiceLogs } from "@/components/monitoring/service-logs"
import { useMonitoringAccess } from "@/lib/auth/use-monitoring-access"
import { cn } from "@/lib/utils"
import type { ServiceStatus } from "@/lib/monitoring/api"

/**
 * `value` is optional: a 0-100 reading per service (health, success rate...).
 * The arc is shaded red -> yellow -> brown -> green along its length and is
 * revealed as it fills, so a full arc shows the whole spectrum ending green.
 * Without a value, an up service reads 100 and a down service reads 0.
 */
export type GaugeService = ServiceStatus & { value?: number }

const COLOR = {
  good: "var(--status-good)",
  yellow: "var(--status-yellow, #d9a400)",
  brown: "var(--status-warn, #8a6400)",
  bad: "var(--status-bad)",
}

/** Where along the arc (0-1) each colour is at full strength. */
const STOPS: [number, string][] = [
  [0, COLOR.bad],
  [0.34, COLOR.yellow],
  [0.67, COLOR.brown],
  [1, COLOR.good],
]

/** Readings at which the outer band turns brown (warning), then rust (critical). */
const WARN_AT = 75
const CRIT_AT = 90

/** Each service's history is sampled this often and capped at this length. */
const SAMPLE_MS = 5000
const MAX_SAMPLES = 40

// Gauge geometry: a 270-degree arc opening at the bottom.
const CX = 80
const CY = 80
const R = 50
const STROKE = 11
const R_ZONE = 61 // the thin warning band sits just outside the arc
const START = 135
const SWEEP = 270

const clamp = (n: number, lo = 0, hi = 100) => Math.min(hi, Math.max(lo, n))
const keyOf = (s: ServiceStatus) => `${s.id}-${s.scraped}`
const readingOf = (s: GaugeService) => (s.up ? clamp(s.value ?? 100) : 0)

/** Colour of one point along the arc, blended between the two nearest stops. */
function shade(f: number) {
  for (let i = 1; i < STOPS.length; i++) {
    const [f0, c0] = STOPS[i - 1]
    const [f1, c1] = STOPS[i]
    if (f <= f1) {
      const t = (f - f0) / (f1 - f0)
      return `color-mix(in srgb, ${c0} ${Math.round((1 - t) * 100)}%, ${c1})`
    }
  }
  return STOPS[STOPS.length - 1][1]
}

/** Colour for the number, which steps through the same colours as it counts up. */
function toneAt(p: number) {
  return p < 17 ? COLOR.bad : p < 50 ? COLOR.yellow : p < 83 ? COLOR.brown : COLOR.good
}

function formatPct(p: number) {
  return p >= 10 ? `${Math.round(p)}%` : `${p.toFixed(1)}%`
}

function polar(r: number, deg: number): [number, number] {
  const a = (deg * Math.PI) / 180
  return [CX + r * Math.cos(a), CY + r * Math.sin(a)]
}

function arcPath(from: number, to: number, r: number) {
  const [x1, y1] = polar(r, from)
  const [x2, y2] = polar(r, to)
  return `M ${x1} ${y1} A ${r} ${r} 0 ${to - from > 180 ? 1 : 0} 1 ${x2} ${y2}`
}

const atPct = (p: number) => START + (SWEEP * p) / 100
const TRACK = arcPath(START, START + SWEEP, R)
const ZONE_WARN = arcPath(atPct(WARN_AT), atPct(CRIT_AT), R_ZONE)
const ZONE_CRIT = arcPath(atPct(CRIT_AT), atPct(100), R_ZONE)

// The shaded spectrum is cut into short arcs, each its own colour, which
// gives a true angular gradient (SVG has no conic gradient). A mask reveals
// as much of it as the reading, so the shading appears as the arc fills.
const SEG = 48
const SEGMENTS = Array.from({ length: SEG }, (_, i) => {
  const a = START + (SWEEP * i) / SEG
  const b = START + (SWEEP * (i + 1)) / SEG
  return {
    d: arcPath(a, Math.min(b + 0.7, START + SWEEP), R),
    color: shade((i + 0.5) / SEG),
  }
})

const REDUCED_MOTION = "(prefers-reduced-motion: reduce)"

function subscribeReducedMotion(onChange: () => void) {
  const query = window.matchMedia(REDUCED_MOTION)
  query.addEventListener("change", onChange)
  return () => query.removeEventListener("change", onChange)
}

/**
 * Whether the person asked for less motion. Read through
 * useSyncExternalStore, so it also follows the setting changing while the
 * page is open, and the server render (where there is no window to ask)
 * agrees with the first client render instead of mismatching it.
 */
function usePrefersReducedMotion() {
  return useSyncExternalStore(
    subscribeReducedMotion,
    () => window.matchMedia(REDUCED_MOTION).matches,
    () => false
  )
}

/** Eases 0 -> target on mount (after `delay`), and glides on later changes. */
function useCountUp(target: number, delay = 0, ms = 1000) {
  const reduced = usePrefersReducedMotion()
  const [animated, setAnimated] = useState(0)
  const cur = useRef(0)
  const first = useRef(true)

  useEffect(() => {
    // Reduced motion draws no animation, so there is nothing to run - and,
    // unlike the version that set state here, nothing to set. The return
    // value below is the target itself.
    if (reduced) return
    const from = cur.current
    const start = performance.now() + (first.current ? delay : 0)
    first.current = false
    let raf = 0
    const step = (now: number) => {
      if (now < start) {
        raf = requestAnimationFrame(step)
        return
      }
      const p = Math.min(1, (now - start) / ms)
      const e = 1 - Math.pow(1 - p, 3)
      cur.current = from + (target - from) * e
      setAnimated(cur.current)
      if (p < 1) raf = requestAnimationFrame(step)
    }
    raf = requestAnimationFrame(step)
    return () => cancelAnimationFrame(raf)
  }, [target, delay, ms, reduced])

  return reduced ? target : animated
}

/**
 * A faint full-spectrum track with the same shading revealed on top up to the
 * reading. The arc and the number sweep up together when the popup opens.
 */
function Gauge({ pct, index }: { pct: number; index: number }) {
  const v = useCountUp(pct, 150 + index * 50)
  const uid = useId().replace(/:/g, "")
  const specId = `spec-${uid}`
  const maskId = `mask-${uid}`

  // The outer band draws in once the arc has mostly settled.
  const [on, setOn] = useState(false)
  useEffect(() => {
    const id = setTimeout(() => setOn(true), 600 + index * 50)
    return () => clearTimeout(id)
  }, [index])

  return (
    <svg
      viewBox="0 14 160 114"
      className="w-full overflow-visible"
      role="img"
      aria-label={formatPct(pct)}
    >
      <defs>
        <g id={specId}>
          {SEGMENTS.map((seg, i) => (
            <path
              key={i}
              d={seg.d}
              fill="none"
              strokeWidth={STROKE}
              style={{ stroke: seg.color }}
            />
          ))}
        </g>
        <mask
          id={maskId}
          maskUnits="userSpaceOnUse"
          x="0"
          y="0"
          width="160"
          height="160"
        >
          <path
            d={TRACK}
            pathLength={100}
            fill="none"
            stroke="white"
            strokeWidth={STROKE + 2}
            strokeDasharray={`${v} 100`}
          />
        </mask>
      </defs>

      <use href={`#${specId}`} opacity={0.16} />
      <g mask={`url(#${maskId})`}>
        <use href={`#${specId}`} />
      </g>

      {/* Outer band: brown warning zone, then rust critical zone */}
      {[
        [ZONE_WARN, COLOR.brown],
        [ZONE_CRIT, COLOR.bad],
      ].map(([d, c], i) => (
        <path
          key={i}
          d={d}
          pathLength={100}
          fill="none"
          stroke={c}
          strokeWidth={2.5}
          strokeDasharray={`${on ? 100 : 0} 100`}
          className="transition-[stroke-dasharray] duration-700 ease-out motion-reduce:transition-none"
          style={{ transitionDelay: `${i * 300}ms` }}
        />
      ))}

      <text
        x={CX}
        y={CY + 8}
        textAnchor="middle"
        className="text-[24px] font-semibold tabular-nums transition-[fill] duration-500"
        style={{ fill: toneAt(v) }}
      >
        {formatPct(v)}
      </text>
    </svg>
  )
}

/** One gauge card inside the popup. */
function ServiceTile({
  service,
  index,
  selected,
  onSelect,
}: {
  service: GaugeService
  index: number
  selected: boolean
  onSelect: () => void
}) {
  const Icon = service.up ? CircleCheck : CircleX
  const statusColor = service.up ? COLOR.good : COLOR.bad

  return (
    <button
      type="button"
      onClick={onSelect}
      aria-pressed={selected}
      className={cn(
        "flex min-w-0 flex-col rounded-xl border p-3 text-left transition-[border-color,box-shadow] duration-200",
        "hover:shadow-md focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none",
        "motion-reduce:transition-none",
        service.up
          ? "border-border bg-card hover:border-foreground/30"
          : "border-[var(--status-bad)] bg-[var(--rust-tint)]",
        selected && "ring-2 ring-foreground/60"
      )}
    >
      <div className="flex items-center gap-1.5">
        <Icon
          size={14}
          style={{ color: statusColor }}
          aria-hidden="true"
          className={cn(!service.up && "motion-safe:animate-pulse")}
        />
        <span
          className="text-[12px] font-semibold tracking-[0.03em] uppercase"
          style={{ color: statusColor }}
        >
          {service.up ? "Up" : "Down"}
        </span>
        {!service.scraped && (
          <Radio
            size={11}
            className="ml-auto text-[var(--ink-faint)]"
            aria-label="Checked by reaching it, not by its own metrics"
          />
        )}
      </div>
      <div className="mx-auto my-1.5 w-full max-w-[130px]">
        <Gauge pct={readingOf(service)} index={index} />
      </div>
      <p
        className="truncate text-center text-[12.5px] font-medium"
        title={service.label}
      >
        {service.label}
      </p>
    </button>
  )
}

/**
 * What this page has observed since it loaded, not stored history. Fixed
 * 0-100 scale, so a flat line at the top reads as "steady at full".
 */
function Sparkline({ data, color }: { data: number[]; color: string }) {
  const [hover, setHover] = useState<number | null>(null)
  const H = 56

  if (data.length < 2) {
    return (
      <div className="flex h-[56px] items-center text-[12px] text-muted-foreground">
        Collecting samples…
      </div>
    )
  }

  const last = data.length - 1
  const xs = data.map((_, i) => (i / last) * 100)
  const ys = data.map((d) => H - 4 - (d / 100) * (H - 8))
  const line = xs.map((x, i) => `${i ? "L" : "M"} ${x} ${ys[i]}`).join(" ")
  const idx = Math.min(hover ?? last, last)
  const ago = (last - idx) * (SAMPLE_MS / 1000)

  return (
    <div>
      <p className="mb-1 text-[12px] text-muted-foreground tabular-nums">
        <span className="font-medium text-foreground">
          {formatPct(data[idx])}
        </span>{" "}
        {idx === last ? "now" : `${ago}s ago`}
      </p>
      <div
        className="relative h-[56px] w-full"
        onPointerMove={(e) => {
          const r = e.currentTarget.getBoundingClientRect()
          setHover(Math.round(clamp((e.clientX - r.left) / r.width, 0, 1) * last))
        }}
        onPointerLeave={() => setHover(null)}
      >
        <svg
          viewBox={`0 0 100 ${H}`}
          preserveAspectRatio="none"
          className="h-full w-full"
          aria-hidden="true"
        >
          <path d={`${line} L 100 ${H} L 0 ${H} Z`} fill={color} fillOpacity={0.1} />
          <path
            d={line}
            fill="none"
            stroke={color}
            strokeWidth={1.75}
            vectorEffect="non-scaling-stroke"
            strokeLinejoin="round"
          />
        </svg>
        <span
          aria-hidden="true"
          className="pointer-events-none absolute size-2 -translate-x-1/2 -translate-y-1/2 rounded-full ring-2 ring-card"
          style={{
            left: `${xs[idx]}%`,
            top: `${(ys[idx] / H) * 100}%`,
            background: color,
          }}
        />
      </div>
    </div>
  )
}

function ServiceDetail({
  service,
  data,
  onClose,
}: {
  service: GaugeService
  data: number[]
  onClose: () => void
}) {
  const { canViewLogs } = useMonitoringAccess()
  const statusColor = service.up ? COLOR.good : COLOR.bad
  const pct = readingOf(service)
  const min = data.length ? Math.min(...data) : pct
  const max = data.length ? Math.max(...data) : pct

  return (
    <div
      role="region"
      aria-label={`Details for ${service.label}`}
      className="rounded-xl border border-border bg-muted/30 p-4"
    >
      <div className="flex items-start gap-2">
        <div className="min-w-0 flex-1">
          <p className="truncate text-[14px] font-semibold" title={service.label}>
            {service.label}
          </p>
          <p
            className="mt-0.5 text-[12px] font-semibold tracking-[0.03em] uppercase"
            style={{ color: statusColor }}
          >
            {service.up ? "Up" : "Down"}
          </p>
        </div>
        <button
          type="button"
          onClick={onClose}
          aria-label="Clear selection"
          className="rounded p-0.5 text-muted-foreground hover:bg-muted hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
        >
          <X size={14} />
        </button>
      </div>
      <dl className="mt-3 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-[12.5px] text-muted-foreground">
        <dt>Reading</dt>
        <dd className="text-foreground tabular-nums">{formatPct(pct)}</dd>
        {data.length > 1 && (
          <>
            <dt>Range</dt>
            <dd className="tabular-nums">
              {formatPct(min)} – {formatPct(max)}
            </dd>
          </>
        )}
        <dt>Checked by</dt>
        <dd>{service.scraped ? "Its own metrics" : "Being reached (probe)"}</dd>
        <dt>ID</dt>
        <dd className="truncate font-mono text-[11.5px]">{String(service.id)}</dd>
      </dl>
      <p className="mt-4 mb-1 text-[12px] font-medium">Since page load</p>
      <Sparkline data={data} color={statusColor} />
      {/* The service id is the compose service name, which is also the
          `service` label Promtail puts on every log line - so no lookup. */}
      {canViewLogs && (
        <ServiceLogs serviceId={String(service.id)} serviceLabel={service.label} />
      )}
    </div>
  )
}

type Filter = "all" | "down" | "up"

/**
 * Popup with every service at once. Gauge cards on the left, and the details
 * of the selected one on the right (stacked on top on small screens).
 */
function DetailsModal({
  services,
  history,
  onClose,
}: {
  services: GaugeService[]
  history: Record<string, number[]>
  onClose: () => void
}) {
  const panelRef = useRef<HTMLDivElement>(null)
  const closeRef = useRef(onClose)
  const [shown, setShown] = useState(false)
  const [filter, setFilter] = useState<Filter>("all")
  const [selectedKey, setSelectedKey] = useState<string | null>(null)

  useEffect(() => {
    closeRef.current = onClose
  }, [onClose])

  useEffect(() => {
    const prev = document.activeElement as HTMLElement | null
    const overflow = document.body.style.overflow
    document.body.style.overflow = "hidden"
    panelRef.current?.focus()
    const raf = requestAnimationFrame(() => setShown(true))
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") closeRef.current()
    }
    document.addEventListener("keydown", onKey)
    return () => {
      cancelAnimationFrame(raf)
      document.removeEventListener("keydown", onKey)
      document.body.style.overflow = overflow
      prev?.focus?.()
    }
  }, [])

  const downCount = services.filter((s) => !s.up).length
  const upCount = services.length - downCount
  // Problems first.
  const list = services
    .filter((s) => (filter === "all" ? true : filter === "down" ? !s.up : s.up))
    .sort((a, b) => Number(a.up) - Number(b.up))
  const selected = services.find((s) => keyOf(s) === selectedKey) ?? null

  const chips: [Filter, string][] = [
    ["all", `All ${services.length}`],
    ["down", `Down ${downCount}`],
    ["up", `Up ${upCount}`],
  ]

  return createPortal(
    <div className="fixed inset-0 z-50 flex items-end justify-center p-0 sm:items-center sm:p-6">
      <div
        aria-hidden="true"
        onClick={onClose}
        className={cn(
          "absolute inset-0 bg-black/50 transition-opacity duration-200 motion-reduce:transition-none",
          shown ? "opacity-100" : "opacity-0"
        )}
      />
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-label="All services"
        tabIndex={-1}
        className={cn(
          "relative flex max-h-[92vh] w-full max-w-6xl flex-col overflow-hidden rounded-t-2xl border border-border bg-card shadow-xl outline-none sm:rounded-2xl",
          "transition-[opacity,transform] duration-200 ease-out motion-reduce:transition-none",
          shown ? "translate-y-0 opacity-100" : "translate-y-4 opacity-0"
        )}
      >
        <div className="flex flex-wrap items-center gap-3 border-b border-border p-4">
          <div className="min-w-0 flex-1">
            <h2 className="text-[15px] font-semibold">All services</h2>
            <p className="mt-0.5 text-[12.5px] text-muted-foreground">
              Select a service to see its details.
            </p>
          </div>
          <div className="flex gap-1.5" role="group" aria-label="Filter by status">
            {chips.map(([key, label]) => (
              <button
                key={key}
                type="button"
                onClick={() => setFilter(key)}
                aria-pressed={filter === key}
                className={cn(
                  "rounded-full border px-2.5 py-1 text-[12px] font-medium transition-colors",
                  "focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none",
                  filter === key
                    ? "border-foreground bg-foreground text-background"
                    : "border-border text-muted-foreground hover:bg-muted hover:text-foreground"
                )}
              >
                {label}
              </button>
            ))}
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="rounded p-1 text-muted-foreground hover:bg-muted hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
          >
            <X size={18} />
          </button>
        </div>

        <div className="grid min-h-0 flex-1 gap-4 overflow-y-auto p-4 lg:grid-cols-[minmax(0,1fr)_300px]">
          {selected && (
            <div className="lg:order-2">
              <div className="lg:sticky lg:top-0">
                <ServiceDetail
                  service={selected}
                  data={history[keyOf(selected)] ?? []}
                  onClose={() => setSelectedKey(null)}
                />
              </div>
            </div>
          )}
          <div
            className={cn(
              "grid h-fit grid-cols-2 gap-2.5 sm:grid-cols-3 md:grid-cols-4",
              selected ? "xl:grid-cols-4" : "lg:grid-cols-5 xl:grid-cols-6",
              !selected && "lg:col-span-2"
            )}
          >
            {list.length === 0 ? (
              <p className="col-span-full py-8 text-center text-[13px] text-muted-foreground">
                No services match this filter.
              </p>
            ) : (
              list.map((service, i) => {
                const k = keyOf(service)
                return (
                  <ServiceTile
                    key={k}
                    service={service}
                    index={i}
                    selected={k === selectedKey}
                    onSelect={() => setSelectedKey(k === selectedKey ? null : k)}
                  />
                )
              })
            )}
          </div>
        </div>

        <p className="border-t border-border px-4 py-2.5 text-[11.5px] text-muted-foreground">
          <Radio size={11} className="mr-1 inline" aria-hidden="true" />
          marks a service checked by reaching it, rather than one reporting its
          own metrics.
        </p>
      </div>
    </div>,
    document.body
  )
}

/** The page itself: one summary card with a "More details" button. */
/**
 * One service as a segment of the status strip.
 *
 * A real button rather than a decorative span: it takes focus, and it opens
 * the same details as the "More details" button, so the strip is a way IN
 * and not just a picture.
 *
 * The label is a styled popover rather than the `title` attribute. A title
 * waits about a second before it appears, never appears on touch or on
 * keyboard focus, and cannot be read by anyone who tabs through. This shows
 * on hover AND focus.
 *
 * Status is not colour alone: a down segment carries an X as well, and the
 * strip's caption names what is down in words.
 */
function StatusSegment({
  service,
  edge,
  onSelect,
}: {
  service: GaugeService
  /** The first and last labels are anchored to their own edge so they cannot hang off the card. */
  edge: "start" | "mid" | "end"
  onSelect: () => void
}) {
  const colour = service.up ? COLOR.good : COLOR.bad
  const side =
    edge === "start"
      ? "left-0"
      : edge === "end"
        ? "right-0"
        : "left-1/2 -translate-x-1/2"

  return (
    <button
      type="button"
      onClick={onSelect}
      aria-label={`${service.label}: ${service.up ? "up" : "down"}. Open details.`}
      className={cn(
        "group relative flex h-full min-w-[6px] flex-1 items-center justify-center rounded-md transition-[filter] duration-150",
        "hover:brightness-110 focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-1 focus-visible:outline-none",
        !service.up && "motion-safe:animate-pulse"
      )}
      style={{ background: colour }}
    >
      {!service.up && (
        <X size={14} strokeWidth={3} className="text-white" aria-hidden="true" />
      )}
      <span
        role="tooltip"
        className={cn(
          "pointer-events-none absolute bottom-full z-20 mb-2 rounded-md border border-border bg-popover px-2 py-1 text-[12px] font-medium whitespace-nowrap text-popover-foreground opacity-0 shadow-md transition-opacity duration-150",
          "group-hover:opacity-100 group-focus-visible:opacity-100",
          side
        )}
      >
        {service.label}
        <span
          className="ml-1.5 font-semibold"
          style={{ color: colour }}
        >
          {service.up ? "Up" : "Down"}
        </span>
      </span>
    </button>
  )
}

export function ServiceStatusList({
  services,
  className,
}: {
  services: GaugeService[]
  className?: string
}) {
  const [open, setOpen] = useState(false)
  const [history, setHistory] = useState<Record<string, number[]>>({})

  // Sample every service on a timer so the details have a trend to show,
  // even while the popup is closed. The ref is written in its own effect,
  // not during render.
  const latest = useRef(services)
  useEffect(() => {
    latest.current = services
  }, [services])
  useEffect(() => {
    const sample = () =>
      setHistory((prev) => {
        const next = { ...prev }
        for (const s of latest.current) {
          const k = keyOf(s)
          next[k] = [...(next[k] ?? []), readingOf(s)].slice(-MAX_SAMPLES)
        }
        return next
      })
    sample()
    const id = setInterval(sample, SAMPLE_MS)
    return () => clearInterval(id)
  }, [])

  const close = useCallback(() => setOpen(false), [])

  const total = services.length
  const down = services.filter((s) => !s.up)
  const upCount = total - down.length
  const average = total
    ? Math.round(services.reduce((sum, s) => sum + readingOf(s), 0) / total)
    : 0
  const allUp = total > 0 && down.length === 0

  return (
    <div className={cn("rounded-xl border border-border bg-card", className)}>
      <div className="flex flex-wrap items-center gap-x-8 gap-y-4 p-4">
        {/* Overall reading */}
        <div className="flex items-center gap-3">
          {total > 0 &&
            (allUp ? (
              <CircleCheck size={28} style={{ color: COLOR.good }} aria-hidden="true" />
            ) : (
              <CircleX size={28} style={{ color: COLOR.bad }} aria-hidden="true" />
            ))}
          <div>
            <h2 className="text-[15px] font-semibold">Services</h2>
            <p className="text-[13px] text-muted-foreground">
              {total === 0
                ? "Nothing is being monitored."
                : allUp
                  ? `All ${total} services are answering.`
                  : `${upCount} of ${total} answering, ${down.length} down.`}
            </p>
          </div>
        </div>

        {total > 0 && (
          <>
            {/* Stats */}
            <dl className="flex gap-6 text-[12px] text-muted-foreground">
              <div>
                <dt>Up</dt>
                <dd
                  className="text-[18px] font-semibold tabular-nums"
                  style={{ color: COLOR.good }}
                >
                  {upCount}
                </dd>
              </div>
              <div>
                <dt>Down</dt>
                <dd
                  className="text-[18px] font-semibold tabular-nums"
                  style={{ color: down.length ? COLOR.bad : undefined }}
                >
                  {down.length}
                </dd>
              </div>
              <div>
                <dt>Average</dt>
                <dd
                  className="text-[18px] font-semibold tabular-nums"
                  style={{ color: toneAt(average) }}
                >
                  {average}%
                </dd>
              </div>
            </dl>

            {/* One segment per service, sharing the free width equally.
                It was a row of dots capped at 260px, which wrapped at 14 and
                stranded two on a second line, left a gap before the button,
                and could not say which dot was which. A strip fills the space
                it is given, never wraps, and each segment can be named. */}
            <div className="min-w-[220px] flex-1 basis-[260px]">
              <div
                className="flex h-8 gap-[3px]"
                role="group"
                aria-label={`${upCount} of ${total} services up`}
              >
                {services.map((s, i) => (
                  <StatusSegment
                    key={keyOf(s)}
                    service={s}
                    edge={i === 0 ? "start" : i === total - 1 ? "end" : "mid"}
                    onSelect={() => setOpen(true)}
                  />
                ))}
              </div>
              {/* Name what is wrong. "2 down" says how many; the person
                  reading needs which, and should not have to hover 16
                  segments to find out. */}
              {down.length > 0 && (
                <p
                  className="mt-1.5 truncate text-[12px] font-medium"
                  style={{ color: COLOR.bad }}
                  title={down.map((s) => s.label).join(", ")}
                >
                  {down.map((s) => s.label).join(", ")}{" "}
                  {down.length === 1 ? "is" : "are"} not answering
                </p>
              )}
            </div>
          </>
        )}

        {total > 0 && (
          <button
            type="button"
            onClick={() => setOpen(true)}
            aria-haspopup="dialog"
            className="ml-auto border border-border transition-colors hover:bg-muted focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none h-[38px] rounded-btn px-4 text-[13px] font-semibold inline-flex items-center justify-center"
          >
            More details
          </button>
        )}
      </div>

      {down.length > 0 && (
        <p
          className="border-t border-border px-4 py-2.5 text-[12.5px]"
          style={{ color: COLOR.bad }}
        >
          Down:{" "}
          <span className="font-medium">
            {down
              .slice(0, 4)
              .map((s) => s.label)
              .join(", ")}
            {down.length > 4 && ` +${down.length - 4} more`}
          </span>
        </p>
      )}

      {open && (
        <DetailsModal services={services} history={history} onClose={close} />
      )}
    </div>
  )
}