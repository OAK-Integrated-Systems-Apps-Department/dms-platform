"use client"

import { useId, useState, type CSSProperties, type ReactNode } from "react"
import {
  ArrowRight,
  CalendarRange,
  Droplets,
  Files,
  Calculator,
  Info,
  Layers,
  Leaf,
  Loader2,
} from "lucide-react"

import { NUMBER, niceCeiling, share } from "@/components/dashboard/charts"
import {
  DashCard,
  DashSkeleton,
  SectionError,
} from "@/components/dashboard/dash-card"
import { useBox } from "@/components/dashboard/motion"
import { fileKind } from "@/components/documents/browser-items"
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip"
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet"
import { friendlyMessage } from "@/lib/api-error"
import { getTooltip } from "@/lib/tooltips"
import {
  useImpact,
  type DigitalImpactDto,
  type ImpactRange,
  type PageKind,
} from "@/lib/dashboard/impact"
import { cn } from "@/lib/utils"

/** The forest green of the brand, and a soft sage made from it. */
const FOREST = "var(--primary)"
const SAGE = "color-mix(in oklab, var(--primary) 30%, var(--card))"

/**
 * Puts the explanation in a hover/focus tooltip so the card itself can stay
 * short. A focusable span, so keyboard users get it too.
 */
function Hint({
  text,
  children,
  className,
}: {
  text: ReactNode
  children: ReactNode
  className?: string
}) {
  return (
    <Tooltip>
      <TooltipTrigger
        render={(props) => (
          <span
            {...props}
            tabIndex={0}
            className={cn(
              "cursor-help focus-visible:ring-2 focus-visible:ring-white/60 focus-visible:outline-none",
              className
            )}
          >
            {children}
          </span>
        )}
      />
      <TooltipContent>{text}</TooltipContent>
    </Tooltip>
  )
}

type Kind = "Measured" | "Calculated" | "Estimated"

const KIND_STYLE: Record<Kind, string> = {
  Measured: "bg-[var(--primary-tint)] text-primary",
  Calculated: "border border-[var(--border-strong)] text-foreground",
  Estimated:
    "bg-[var(--gold-tint)] text-[var(--gold-foreground)] dark:text-[var(--gold)]",
}

/** A quantity at a sensible precision: 2.49, 24.9, 249, 2,490. */
function amount(value: number): string {
  if (value === 0) return "0"
  if (value < 0.01) return "<0.01"
  if (value < 10) return value.toFixed(2)
  if (value < 100) return value.toFixed(1)
  return NUMBER.format(Math.round(value))
}

/** A round axis top for fractional measures (kg) - 0.25, 0.5, 1, 2.5... - at least `value`. */
function niceDecimal(value: number): number {
  if (value <= 0) return 1
  const power = Math.pow(10, Math.floor(Math.log10(value)))
  return ([1, 2, 2.5, 5, 10].find((s) => s * power >= value) ?? 10) * power
}

/** Axis figures: whole numbers once the scale reaches 10. */
function axisLabel(value: number, max: number): string {
  return max >= 10 ? NUMBER.format(Math.round(value)) : amount(value)
}

function dayLabel(date: string, long = false) {
  return new Date(`${date}T00:00:00`).toLocaleDateString(undefined, {
    day: "numeric",
    month: "short",
    ...(long ? { year: "numeric" } : {}),
  })
}

function isoDay(date: Date) {
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000)
  return local.toISOString().slice(0, 10)
}

/**
 * Environmental Impact - the pages processed digitally in a period, counted
 * from the files themselves, and the paper and footprint they stand for under
 * the organisation's configured assumptions (GET /api/v1/dashboard/impact).
 * Every figure says whether it's measured, calculated or estimated, and the
 * method is one click away.
 */
export function DigitalImpact({
  order,
  onOpen,
}: {
  order: number
  /** Opens a document in the preview - from "Biggest page savers". */
  onOpen?: (document: DigitalImpactDto["topDocuments"][number]) => void
}) {
  const [range, setRange] = useState<ImpactRange>({ kind: "days", days: 30 })
  const [methodOpen, setMethodOpen] = useState(false)
  const impact = useImpact(range)
  const data = impact.data

  return (
    <DashCard order={order} className="overflow-hidden p-0">
      {/* The forest band: the headline figures, on the brand's deep oak green. */}
      <div className="impact-band relative isolate px-5 pt-5 pb-7 text-white sm:px-6">
        <header className="relative mb-5 flex flex-wrap items-start justify-between gap-3">
          <div className="flex items-start gap-3">
            <span
              aria-hidden="true"
              className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-white/12 text-[#cfe6d6] ring-1 ring-white/20"
            >
              <Leaf size={20} strokeWidth={1.75} />
            </span>
            <div>
              <h2 className="flex items-center gap-2 font-heading text-[18px] font-semibold tracking-[-0.01em]">
                Environmental Impact
                <Hint
                  text={getTooltip("dashboard.impact.about")}
                  className="rounded-full text-white/60 hover:text-white"
                >
                  <Info size={15} aria-label="About this figure" />
                </Hint>
              </h2>
            </div>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <RangePicker range={range} onChange={setRange} />
            <Tooltip>
              <TooltipTrigger
                render={(props) => (
                  <button
                    {...props}
                    type="button"
                    onClick={() => setMethodOpen(true)}
                    aria-label={getTooltip("dashboard.impact.method")}
                    className="inline-flex size-8 items-center justify-center rounded-lg text-[#cfe6d6] transition-colors hover:bg-white/10 focus-visible:ring-2 focus-visible:ring-white/60 focus-visible:outline-none"
                  >
                    <Calculator size={16} aria-hidden="true" />
                  </button>
                )}
              />
              <TooltipContent>
                {getTooltip("dashboard.impact.method")}
              </TooltipContent>
            </Tooltip>
          </div>
        </header>

        {impact.isError && !data ? (
          <div className="rounded-xl bg-white/95 text-foreground">
            <SectionError
              message={friendlyMessage(
                impact.error,
                "The Environmental Impact figures couldn't be loaded."
              )}
              onRetry={() => void impact.refetch()}
            />
          </div>
        ) : !data ? (
          <div aria-busy="true" className="grid gap-3 sm:grid-cols-3">
            <div className="h-[86px] animate-pulse rounded-xl bg-white/10 sm:col-span-3" />
            {[1, 2, 3].map((i) => (
              <div
                key={i}
                className="h-[92px] animate-pulse rounded-xl bg-white/10"
              />
            ))}
          </div>
        ) : (
          <div
            className={cn(
              "relative max-w-[720px] transition-opacity",
              impact.isPlaceholderData && "opacity-60"
            )}
          >
            <BandFigures data={data} />
          </div>
        )}
      </div>

      {/* The analysis, on light. */}
      <div className="p-5 sm:px-6">
        {data ? (
          <div
            className={cn(
              "transition-opacity",
              impact.isPlaceholderData && "opacity-60"
            )}
          >
            <Coverage data={data} />
            <Trend data={data} />
            <div className="grid gap-4 md:grid-cols-2">
              <PageSources data={data} />
              <TopSavers data={data} onOpen={onOpen} />
            </div>
          </div>
        ) : !impact.isError ? (
          <div aria-busy="true">
            <DashSkeleton className="mb-4 h-3 w-2/3" />
            <DashSkeleton className="mb-4 h-[200px] w-full rounded-xl" />
            <DashSkeleton className="h-[56px] w-full rounded-xl" />
          </div>
        ) : null}
      </div>

      {data && (
        <MethodSheet
          open={methodOpen}
          onOpenChange={setMethodOpen}
          data={data}
        />
      )}
    </DashCard>
  )
}

/** 7, 30 or 90 days - or a custom range of days. */
function RangePicker({
  range,
  onChange,
}: {
  range: ImpactRange
  onChange: (range: ImpactRange) => void
}) {
  const today = isoDay(new Date())
  const [custom, setCustom] = useState(range.kind === "custom")
  const [from, setFrom] = useState(() =>
    isoDay(new Date(Date.now() - 29 * 86_400_000))
  )
  const [to, setTo] = useState(today)
  const valid = from && to && from <= to && to <= today

  return (
    <div className="flex flex-wrap items-center gap-2">
      <div
        role="radiogroup"
        aria-label="Environmental Impact period"
        className="flex rounded-lg bg-white/10 p-0.5 ring-1 ring-white/20"
      >
        {([7, 30, 90] as const).map((days) => {
          const on = !custom && range.kind === "days" && range.days === days
          return (
            <button
              key={days}
              type="button"
              role="radio"
              aria-checked={on}
              onClick={() => {
                setCustom(false)
                onChange({ kind: "days", days })
              }}
              className={cn(
                "rounded-md px-2.5 py-1 text-[12px] font-semibold transition-colors focus-visible:ring-2 focus-visible:ring-white/60 focus-visible:outline-none",
                on
                  ? "bg-white text-[#163828] shadow-sm"
                  : "text-white/75 hover:text-white"
              )}
            >
              {days}d
            </button>
          )
        })}
        <button
          type="button"
          role="radio"
          aria-checked={custom}
          onClick={() => setCustom(true)}
          className={cn(
            "inline-flex items-center gap-1 rounded-md px-2.5 py-1 text-[12px] font-semibold transition-colors focus-visible:ring-2 focus-visible:ring-white/60 focus-visible:outline-none",
            custom
              ? "bg-white text-[#163828] shadow-sm"
              : "text-white/75 hover:text-white"
          )}
        >
          <CalendarRange size={13} aria-hidden="true" />
          Custom
        </button>
      </div>
      {custom && (
        <form
          className="flex flex-wrap items-center gap-1.5 text-[12px]"
          onSubmit={(event) => {
            event.preventDefault()
            if (valid) onChange({ kind: "custom", from, to })
          }}
        >
          <label className="sr-only" htmlFor="impact-from">
            From
          </label>
          <input
            id="impact-from"
            type="date"
            value={from}
            max={to || today}
            onChange={(e) => setFrom(e.target.value)}
            className="h-8 rounded-md border-0 bg-white px-2 text-[12px] text-foreground"
          />
          <span className="text-white/70">to</span>
          <label className="sr-only" htmlFor="impact-to">
            To
          </label>
          <input
            id="impact-to"
            type="date"
            value={to}
            min={from}
            max={today}
            onChange={(e) => setTo(e.target.value)}
            className="h-8 rounded-md border-0 bg-white px-2 text-[12px] text-foreground"
          />
          <button
            type="submit"
            disabled={!valid}
            className="h-8 rounded-md bg-[#cfe6d6] px-3 text-[12px] font-semibold text-[#163828] disabled:opacity-50"
          >
            Apply
          </button>
        </form>
      )}
    </div>
  )
}

const KIND_ON_FOREST: Record<Kind, string> = {
  Measured: "bg-[#cfe6d6] text-[#163828]",
  Calculated: "ring-1 ring-white/40 text-white",
  Estimated: "bg-[#f1dfb6] text-[#5e4512]",
}

function KindBadge({ kind, onForest }: { kind: Kind; onForest?: boolean }) {
  const badge = (
    <span
      className={cn(
        "rounded-full px-1.5 py-px text-[10px] font-semibold tracking-wide whitespace-nowrap uppercase",
        onForest ? KIND_ON_FOREST[kind] : KIND_STYLE[kind]
      )}
    >
      {kind}
    </span>
  )
  // On the band the badge says what it means on hover; elsewhere the method
  // sheet already spells it out next to the badge.
  return onForest ? (
    <Hint
      text={getTooltip(`dashboard.impact.${kind.toLowerCase()}`)}
      className="rounded-full"
    >
      {badge}
    </Hint>
  ) : (
    badge
  )
}

/** "in the last 30 days", or the custom dates. */
function periodWords(data: DigitalImpactDto): string {
  const span = data.daily.length
  const today = data.daily[span - 1]?.date === isoDay(new Date())
  return today && [7, 30, 90].includes(span)
    ? `in the last ${span} days`
    : `from ${dayLabel(data.from)} to ${dayLabel(data.to)}`
}

/** The change since the period before, in words that read on the forest band. */
function ForestChange({ now, before }: { now: number; before: number }) {
  if (now === before) return null
  const text =
    before === 0
      ? "New"
      : `${now > before ? "▲" : "▼"} ${share(Math.abs(now - before), before)}`
  return (
    <Hint
      text={before === 0 ? "New this period" : "Change vs the period before"}
      className="rounded-full bg-white/12 px-2 py-0.5 text-[11.5px] font-semibold text-[#cfe6d6] ring-1 ring-white/15"
    >
      {text}
    </Hint>
  )
}

function Tile({
  icon,
  label,
  value,
  unit,
  kind,
  caption,
}: {
  icon: ReactNode
  label: string
  value: string
  unit: string
  kind: Kind
  caption: string
}) {
  return (
    <Hint
      text={caption}
      className="block rounded-xl bg-[rgba(10,28,19,0.45)] p-3.5 ring-1 ring-white/20 backdrop-blur-md transition-colors hover:bg-[rgba(10,28,19,0.55)]"
    >
      <div className="mb-2 flex items-center justify-between gap-2">
        <span className="flex items-center gap-1.5 text-[12px] font-medium text-white/80">
          {icon}
          {label}
        </span>
        <KindBadge kind={kind} onForest />
      </div>
      <div className="flex items-baseline gap-1">
        <span className="font-heading text-[26px] leading-none font-semibold tabular-nums">
          {value}
        </span>
        <span className="text-[13px] font-medium text-white/70">{unit}</span>
      </div>
    </Hint>
  )
}

function BandFigures({ data }: { data: DigitalImpactDto }) {
  const { totals, previous } = data
  return (
    <div>
      <div className="mb-1 flex flex-wrap items-center gap-2 text-[12.5px] font-medium text-white/75">
        <Files size={14} aria-hidden="true" />
        Pages processed
        <KindBadge kind="Measured" onForest />
      </div>
      <div className="flex flex-wrap items-end gap-x-3 gap-y-1">
        <span className="font-heading text-[48px] leading-none font-semibold tracking-[-0.02em] tabular-nums sm:text-[54px]">
          {NUMBER.format(totals.pages)}
        </span>
        <Hint
          text={`Pages kept off paper ${periodWords(data)}`}
          className="pb-1.5 text-[14px] text-white/75"
        >
          pages saved
        </Hint>
      </div>
      <div className="mt-2 flex flex-wrap items-center gap-2">
        <ForestChange now={totals.pages} before={previous.pages} />
        <Hint
          text={`${NUMBER.format(data.allTime.pages)} pages all time · ${amount(data.allTime.paperKg)} kg paper`}
          className="rounded-full bg-white/12 px-2 py-0.5 text-[11.5px] font-semibold text-[#cfe6d6] ring-1 ring-white/15"
        >
          {NUMBER.format(data.allTime.pages)} all time
        </Hint>
      </div>

      <div className="mt-5 grid gap-2.5 sm:grid-cols-3">
        <Tile
          icon={<Layers size={13} aria-hidden="true" />}
          label="Paper avoided"
          value={amount(totals.paperKg)}
          unit="kg"
          kind="Calculated"
          caption={`≈ ${NUMBER.format(Math.round(totals.sheets))} sheets of ${data.method.paperSize}`}
        />
        <Tile
          icon={<Leaf size={13} aria-hidden="true" />}
          label="CO₂e avoided"
          value={amount(totals.co2eKg)}
          unit="kg"
          kind="Estimated"
          caption={getTooltip("dashboard.impact.co2e")}
        />
        <Tile
          icon={<Droplets size={13} aria-hidden="true" />}
          label="Water footprint"
          value={amount(totals.waterLitres)}
          unit="L"
          kind="Estimated"
          caption={getTooltip("dashboard.impact.water")}
        />
      </div>
    </div>
  )
}

/** How much of the period's uploads the figures rest on - stated plainly. */
function coverageText({ coverage }: DigitalImpactDto) {
  const n = NUMBER.format
  const { versions, counted, noPages, pending, unscanned } = coverage
  if (versions === 0)
    return "No documents have been added in this period yet, so there's nothing to measure."
  const parts = [
    `These figures are based on the ${n(counted)} ${counted === 1 ? "file" : "files"} with pages, out of ${n(versions)} added in this period.`,
  ]
  if (noPages > 0) {
    const all = pending === 0 && unscanned === 0
    const which =
      noPages === 1
        ? `${all ? "The other one" : "One other"} is a spreadsheet, image, email or archive, which has`
        : `${all ? `The other ${n(noPages)}` : `${n(noPages)} others`} are spreadsheets, images, emails or archives, which have`
    parts.push(`${which} no pages to count.`)
  }
  if (pending > 0)
    parts.push(
      `${n(pending)} more ${pending === 1 ? "is" : "are"} still being counted.`
    )
  if (unscanned > 0)
    parts.push(
      `${n(unscanned)} ${unscanned === 1 ? "wasn't" : "weren't"} virus-scanned when uploaded, so ${unscanned === 1 ? "it's" : "they're"} left out.`
    )
  parts.push("The savings assume each page would otherwise have been printed.")
  return parts.join(" ")
}

/** On the dashboard only while files are waiting to be counted - the full note is in the method sheet. */
function Coverage({ data }: { data: DigitalImpactDto }) {
  const { pending } = data.coverage
  if (pending === 0) return null
  return (
    <p className="mb-4 flex items-center gap-1.5 text-[11.5px] text-muted-foreground">
      <Loader2 size={13} className="shrink-0 animate-spin" aria-hidden="true" />
      {NUMBER.format(pending)} {pending === 1 ? "file is" : "files are"} still
      being counted - the figures will update in a few minutes.
    </p>
  )
}

const METRICS = [
  { key: "pages", label: "Pages", unit: "" },
  { key: "paperKg", label: "Paper", unit: "kg" },
  { key: "co2eKg", label: "CO₂e", unit: "kg" },
] as const

type MetricKey = (typeof METRICS)[number]["key"]

const HEIGHT = 170
const PAD = { top: 10, right: 6, bottom: 22, left: 40 }

/** The chosen figure per day over the period, as a filled line. */
function Trend({ data }: { data: DigitalImpactDto }) {
  const [metric, setMetric] = useState<MetricKey>("pages")
  const [frame, box] = useBox<HTMLDivElement>()
  const [hover, setHover] = useState<number | null>(null)
  const id = useId().replace(/:/g, "")
  const days = data.daily
  const values = days.map((d) => d[metric])
  const peak = Math.max(0, ...values)
  const max = metric === "pages" ? niceCeiling(peak) : niceDecimal(peak)
  const width = box.width
  const innerWidth = Math.max(0, width - PAD.left - PAD.right)
  const innerHeight = HEIGHT - PAD.top - PAD.bottom
  const x = (i: number) =>
    PAD.left + (days.length > 1 ? (i / (days.length - 1)) * innerWidth : 0)
  const y = (v: number) => PAD.top + innerHeight - (v / max) * innerHeight
  const line = values.map((v, i) => `${i ? "L" : "M"}${x(i)},${y(v)}`).join(" ")
  const area = `${line} L${x(days.length - 1)},${PAD.top + innerHeight} L${x(0)},${PAD.top + innerHeight} Z`
  const allZero = values.every((v) => v === 0)
  const unit = METRICS.find((m) => m.key === metric)?.unit ?? ""
  const shown = hover !== null ? days[hover] : null
  const labelEvery = Math.max(1, Math.ceil(days.length / 6))

  return (
    <div
      className="mb-4 rounded-xl border p-3.5"
      style={{
        borderColor: "color-mix(in oklab, var(--primary) 16%, var(--border))",
        background: `linear-gradient(180deg, color-mix(in oklab, ${SAGE} 22%, var(--card)), var(--card) 70%)`,
      }}
    >
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <span className="text-[12.5px] font-semibold">Savings over time</span>
        <div
          role="radiogroup"
          aria-label="Savings measure"
          className="flex rounded-md bg-muted p-0.5"
        >
          {METRICS.map((m) => (
            <button
              key={m.key}
              type="button"
              role="radio"
              aria-checked={metric === m.key}
              onClick={() => setMetric(m.key)}
              className={cn(
                "rounded px-2 py-0.5 text-[11.5px] font-semibold transition-colors",
                metric === m.key
                  ? "bg-card text-foreground shadow-sm"
                  : "text-muted-foreground hover:text-foreground"
              )}
            >
              {m.label}
            </button>
          ))}
        </div>
      </div>

      <div ref={frame} className="relative">
        {allZero && (
          <p className="absolute inset-x-0 top-[38%] z-10 text-center text-[12px] text-muted-foreground">
            Nothing to show for this period yet.
          </p>
        )}
        {width > 0 && (
          <svg
            width={width}
            height={HEIGHT}
            role="img"
            aria-label={`${METRICS.find((m) => m.key === metric)?.label} per day, ${dayLabel(data.from)} to ${dayLabel(data.to)}`}
            className="block"
            onPointerLeave={() => setHover(null)}
            onPointerMove={(event) => {
              if (innerWidth <= 0 || days.length < 2) return
              const left = event.currentTarget.getBoundingClientRect().left
              const i = Math.round(
                ((event.clientX - left - PAD.left) / innerWidth) *
                  (days.length - 1)
              )
              setHover(Math.min(days.length - 1, Math.max(0, i)))
            }}
          >
            <defs>
              <linearGradient id={`impact-${id}`} x1="0" y1="0" x2="0" y2="1">
                <stop
                  offset="0%"
                  style={{ stopColor: FOREST, stopOpacity: 0.32 }}
                />
                <stop
                  offset="100%"
                  style={{ stopColor: FOREST, stopOpacity: 0.02 }}
                />
              </linearGradient>
            </defs>
            {[0, 0.5, 1].map((f) => (
              <g key={f}>
                <line
                  x1={PAD.left}
                  x2={width - PAD.right}
                  y1={y(max * f)}
                  y2={y(max * f)}
                  strokeDasharray={f === 0 ? undefined : "3 5"}
                  style={{ stroke: "var(--border)" }}
                />
                <text
                  x={PAD.left - 8}
                  y={y(max * f)}
                  dy="0.32em"
                  textAnchor="end"
                  className="fill-[var(--ink-faint)] text-[10px] tabular-nums"
                >
                  {axisLabel(max * f, max)}
                </text>
              </g>
            ))}
            {days.map((d, i) =>
              i % labelEvery === (days.length - 1) % labelEvery ? (
                <text
                  key={d.date}
                  x={x(i)}
                  y={HEIGHT - 5}
                  textAnchor={
                    i === days.length - 1 ? "end" : i === 0 ? "start" : "middle"
                  }
                  className="fill-[var(--ink-faint)] text-[10px]"
                >
                  {dayLabel(d.date)}
                </text>
              ) : null
            )}
            {!allZero && (
              <>
                <path d={area} style={{ fill: `url(#impact-${id})` }} />
                <path
                  d={line}
                  fill="none"
                  strokeWidth={2.2}
                  strokeLinejoin="round"
                  style={{ stroke: FOREST }}
                />
              </>
            )}
            {shown && hover !== null && (
              <g pointerEvents="none">
                <line
                  x1={x(hover)}
                  x2={x(hover)}
                  y1={PAD.top}
                  y2={PAD.top + innerHeight}
                  strokeDasharray="3 4"
                  style={{ stroke: "var(--border-strong)" }}
                />
                <circle
                  cx={x(hover)}
                  cy={y(values[hover])}
                  r={4.5}
                  style={{ fill: "var(--card)", stroke: FOREST }}
                  strokeWidth={2}
                />
              </g>
            )}
          </svg>
        )}
        {shown && hover !== null && (
          <div
            role="status"
            className="pointer-events-none absolute top-0 z-10 -translate-x-1/2 rounded-lg border border-border bg-popover px-2.5 py-1.5 text-[11.5px] whitespace-nowrap shadow-[var(--ledger-shadow)]"
            style={{ left: Math.min(Math.max(x(hover), 70), width - 70) }}
          >
            <div className="font-semibold tabular-nums">
              {metric === "pages"
                ? `${NUMBER.format(shown.pages)} pages`
                : `${amount(shown[metric])} ${unit}`}
            </div>
            <div className="text-muted-foreground">
              {dayLabel(shown.date, true)}
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

/** Each kind of paged file, in the colours used for file types elsewhere on the dashboard. */
const KINDS: Record<PageKind, { label: string; color: string }> = {
  pdf: { label: "PDF", color: "#d13438" },
  word: { label: "Word documents", color: "#2b579a" },
  slides: { label: "Presentations", color: "#c43e1c" },
  scans: { label: "Scanned images (TIFF)", color: "#8764b8" },
  other: { label: "Other", color: "#8b968f" },
}

const panel = "rounded-xl border p-3.5"
const panelStyle = {
  borderColor: "color-mix(in oklab, var(--primary) 16%, var(--border))",
  background: `color-mix(in oklab, ${SAGE} 14%, var(--card))`,
}

/** Which kinds of file the period's pages came from - one bar split by share, then each kind. */
function PageSources({ data }: { data: DigitalImpactDto }) {
  const total = data.sources.reduce((sum, s) => sum + s.pages, 0)
  return (
    <section
      aria-labelledby="sources-title"
      className={panel}
      style={panelStyle}
    >
      <h3 id="sources-title" className="mb-0.5 text-[12.5px] font-semibold">
        Where the pages come from
      </h3>
      <p className="mb-3 text-[11.5px] text-muted-foreground">
        Pages processed {periodWords(data)}, by kind of file
      </p>
      {total === 0 ? (
        <p className="py-4 text-center text-[12px] text-muted-foreground">
          No pages counted in this period yet.
        </p>
      ) : (
        <>
          <div
            role="img"
            aria-label={data.sources
              .map((s) => `${KINDS[s.kind].label}: ${s.pages} pages`)
              .join(", ")}
            className="mb-3 flex h-3 overflow-hidden rounded-full bg-muted"
          >
            {data.sources.map((s, i) => (
              <span
                key={s.kind}
                className="dash-bar-fill h-full border-r-2 border-card last:border-r-0"
                style={
                  {
                    "--dash-order": i,
                    width: `${(s.pages / total) * 100}%`,
                    minWidth: 4,
                    background: KINDS[s.kind].color,
                  } as CSSProperties
                }
              />
            ))}
          </div>
          <ul className="flex flex-col gap-1.5">
            {data.sources.map((s) => (
              <li
                key={s.kind}
                className="grid grid-cols-[auto_1fr_auto_auto] items-center gap-2 text-[12px]"
              >
                <span
                  aria-hidden="true"
                  className="size-2.5 rounded-[3px]"
                  style={{ background: KINDS[s.kind].color }}
                />
                <span className="min-w-0 truncate">
                  {KINDS[s.kind].label}
                  <span className="ml-1 text-muted-foreground">
                    · {NUMBER.format(s.files)}{" "}
                    {s.files === 1 ? "file" : "files"}
                  </span>
                </span>
                <span className="font-semibold tabular-nums">
                  {NUMBER.format(s.pages)} pages
                </span>
                <span className="w-9 text-right text-[11px] text-muted-foreground tabular-nums">
                  {Math.round((s.pages / total) * 100)}%
                </span>
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  )
}

/** The documents that kept the most pages off paper in the period - open one to preview it. */
function TopSavers({
  data,
  onOpen,
}: {
  data: DigitalImpactDto
  onOpen?: (document: DigitalImpactDto["topDocuments"][number]) => void
}) {
  const max = Math.max(1, ...data.topDocuments.map((d) => d.pages))
  return (
    <section
      aria-labelledby="savers-title"
      className={panel}
      style={panelStyle}
    >
      <h3 id="savers-title" className="mb-0.5 text-[12.5px] font-semibold">
        Biggest page savers
      </h3>
      <p className="mb-3 text-[11.5px] text-muted-foreground">
        The documents with the most pages added {periodWords(data)}
      </p>
      {data.topDocuments.length === 0 ? (
        <p className="py-4 text-center text-[12px] text-muted-foreground">
          No pages counted in this period yet.
        </p>
      ) : (
        <ol className="flex flex-col gap-1">
          {data.topDocuments.map((doc, i) => {
            const kind = fileKind(doc.fileName)
            return (
              <li key={doc.documentId}>
                <button
                  type="button"
                  onClick={() => onOpen?.(doc)}
                  disabled={!onOpen}
                  className="flex w-full items-center gap-2.5 rounded-lg px-1.5 py-1 text-left transition-colors hover:bg-card focus-visible:ring-2 focus-visible:ring-ring/50 focus-visible:outline-none disabled:cursor-default"
                >
                  <span className="w-3 shrink-0 text-center font-heading text-[12px] font-semibold text-muted-foreground tabular-nums">
                    {i + 1}
                  </span>
                  <span
                    className={cn(
                      "flex size-7 shrink-0 items-center justify-center rounded-md",
                      kind.tone
                    )}
                  >
                    <kind.icon size={14} aria-hidden="true" />
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-[12.5px] font-medium">
                      {doc.title}
                    </span>
                    <span className="mt-1 block h-1.5 overflow-hidden rounded-full bg-muted">
                      <span
                        className="dash-bar-fill block h-full rounded-full"
                        style={
                          {
                            "--dash-order": i,
                            width: `${Math.max(4, (doc.pages / max) * 100)}%`,
                            background: `linear-gradient(90deg, ${SAGE}, ${FOREST})`,
                          } as CSSProperties
                        }
                      />
                    </span>
                  </span>
                  <span className="shrink-0 text-[12px] font-semibold tabular-nums">
                    {NUMBER.format(doc.pages)}
                    <span className="ml-0.5 font-normal text-muted-foreground">
                      {doc.pages === 1 ? "page" : "pages"}
                    </span>
                  </span>
                </button>
              </li>
            )
          })}
        </ol>
      )}
    </section>
  )
}

/** "How is this calculated?" - the method, the assumptions and what kind of figure each one is. */
function MethodSheet({
  open,
  onOpenChange,
  data,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  data: DigitalImpactDto
}) {
  const m = data.method
  const steps: { kind: Kind; title: string; text: string }[] = [
    {
      kind: "Measured",
      title: "Digital pages processed",
      text: "The pages of every file added in the period, read from the file itself: PDF pages, PowerPoint slides and TIFF frames are counted; Word and OpenDocument files give the page total their app recorded. Spreadsheets, images, email and archives have no pages and aren't counted.",
    },
    {
      kind: "Calculated",
      title: "Sheets of paper",
      text: `Pages ÷ ${m.pagesPerSheet} page${m.pagesPerSheet === 1 ? "" : "s"} per sheet (${m.pagesPerSheet === 2 ? "double-sided" : "single-sided"} printing).`,
    },
    {
      kind: "Calculated",
      title: "Paper mass",
      text: `${m.paperSize} (${m.paperWidthMm} × ${m.paperHeightMm} mm) at ${m.paperGsm} g/m² - ${m.gramsPerPage.toFixed(2)} g per page.`,
    },
    {
      kind: "Estimated",
      title: "CO₂e",
      text: `Paper mass × ${m.co2eKgPerKgPaper} kg CO₂e per kg. ${m.co2eSource}.`,
    },
    {
      kind: "Estimated",
      title: "Water footprint",
      text: `Paper mass × ${NUMBER.format(m.waterLitresPerKgPaper)} L per kg. ${m.waterSource}.`,
    },
  ]
  const rows: [string, string][] = [
    [
      "Data period",
      `${dayLabel(data.from, true)} – ${dayLabel(data.to, true)} (${data.days} days)`,
    ],
    ["Paper", `${m.paperSize}, ${m.paperGsm} gsm`],
    ["Pages per sheet", String(m.pagesPerSheet)],
    ["CO₂e factor", `${m.co2eKgPerKgPaper} kg CO₂e / kg paper`],
    ["Water factor", `${NUMBER.format(m.waterLitresPerKgPaper)} L / kg paper`],
    ["Calculation version", m.calculationVersion],
  ]
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent
        side="right"
        className="w-full gap-0 overflow-y-auto data-[side=right]:w-full data-[side=right]:sm:w-[500px] data-[side=right]:sm:max-w-[calc(100vw-40px)]"
      >
        <SheetHeader className="border-b border-border">
          <SheetTitle className="flex items-center gap-2">
            <Leaf size={18} strokeWidth={1.75} className="text-primary" />
            How is this calculated?
          </SheetTitle>
          <SheetDescription>
            Every Environmental Impact figure, step by step - and how sure each
            one is.
          </SheetDescription>
        </SheetHeader>

        <div className="flex flex-col gap-6 p-5 text-[13px]">
          <p className="flex items-start gap-2 rounded-lg bg-muted/60 p-3 text-[12.5px] text-muted-foreground">
            <Info size={14} className="mt-0.5 shrink-0" aria-hidden="true" />
            <span>{coverageText(data)}</span>
          </p>
          <ol className="flex flex-col">
            {steps.map((step, i) => (
              <li key={step.title} className="flex gap-3">
                <span className="flex flex-col items-center">
                  <span className="flex size-6 items-center justify-center rounded-full bg-[var(--primary-tint)] font-heading text-[11px] font-semibold text-primary">
                    {i + 1}
                  </span>
                  {i < steps.length - 1 && (
                    <span
                      aria-hidden="true"
                      className="w-px flex-1 bg-border"
                    />
                  )}
                </span>
                <div className="pb-4">
                  <div className="mb-1 flex flex-wrap items-center gap-2">
                    <span className="font-semibold">{step.title}</span>
                    <KindBadge kind={step.kind} />
                  </div>
                  <p className="text-muted-foreground">{step.text}</p>
                </div>
              </li>
            ))}
          </ol>

          <section>
            <h3 className="mb-2 font-semibold">Assumptions in use</h3>
            <dl className="rounded-xl border border-border">
              {rows.map(([term, value]) => (
                <div
                  key={term}
                  className="flex justify-between gap-4 border-b border-border px-3 py-2 last:border-b-0"
                >
                  <dt className="text-muted-foreground">{term}</dt>
                  <dd className="text-right font-medium">{value}</dd>
                </div>
              ))}
            </dl>
            <p className="mt-2 text-[12px] text-muted-foreground">
              The factors are the low end of published ranges, so the estimates
              understate rather than overstate. Your administrator can set your
              organisation&apos;s own assumptions.
            </p>
          </section>

          <section>
            <h3 className="mb-2 font-semibold">What the labels mean</h3>
            <ul className="flex flex-col gap-2">
              {(
                [
                  ["Measured", "Read from your documents."],
                  [
                    "Calculated",
                    "Worked out exactly from measured figures and the stated assumptions.",
                  ],
                  [
                    "Estimated",
                    "Depends on published average factors - a fair indication, not a verified saving.",
                  ],
                ] as [Kind, string][]
              ).map(([kind, text]) => (
                <li key={kind} className="flex items-start gap-2">
                  <KindBadge kind={kind} />
                  <span className="text-muted-foreground">{text}</span>
                </li>
              ))}
            </ul>
          </section>

          <section>
            <h3 className="mb-2 font-semibold">This period&apos;s data</h3>
            <p className="flex flex-wrap items-center gap-1.5 text-muted-foreground">
              {NUMBER.format(data.coverage.countedFromFile)} counted from the
              file
              <ArrowRight size={12} aria-hidden="true" />
              {NUMBER.format(data.coverage.reportedByFile)} as the file recorded
              <ArrowRight size={12} aria-hidden="true" />
              {NUMBER.format(data.coverage.noPages)} without pages
              {data.coverage.pending > 0 &&
                ` · ${NUMBER.format(data.coverage.pending)} still being counted`}
            </p>
            <p className="mt-2 text-[12px] text-muted-foreground">
              Only documents you can open are included, and pages of documents
              deleted since aren&apos;t counted - another reason the figures can
              only understate.
            </p>
          </section>
        </div>
      </SheetContent>
    </Sheet>
  )
}
