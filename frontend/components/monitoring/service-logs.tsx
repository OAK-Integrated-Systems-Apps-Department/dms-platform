"use client"

import { useEffect, useRef, useState } from "react"
import { createPortal } from "react-dom"
import { useQuery } from "@tanstack/react-query"
import {
  Check,
  ChevronDown,
  Copy,
  Maximize2,
  Pause,
  Play,
  Search,
  X,
} from "lucide-react"

import { friendlyMessage } from "@/lib/api-error"
import {
  fetchServiceLogs,
  LOG_LEVELS,
  LOG_RANGES,
  type LogLevel,
  type LogRange,
} from "@/lib/monitoring/api"
import { groupLines, type LogGroup } from "@/lib/monitoring/log-format"
import { cn } from "@/lib/utils"

/**
 * How a level looks. WARN and above get a tinted badge and a left rule so
 * they stand out from a screen of routine lines; everything below stays
 * neutral on purpose, because colour only means something if most rows have
 * none. The level is always WRITTEN in the badge as well - nothing here
 * depends on telling the colours apart.
 */
const LEVEL_COLOUR: Record<string, string> = {
  ERROR: "var(--status-bad)",
  FATAL: "var(--status-bad)",
  WARN: "var(--status-warn)",
  WARNING: "var(--status-warn)",
}

function time(at: number, range: LogRange): string {
  const date = new Date(at)
  const clock = date.toLocaleTimeString(undefined, {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  })
  // Over a day the date is what locates a line; inside a day it is noise.
  return range === "24h" || range === "7d"
    ? `${date.toLocaleDateString(undefined, { day: "2-digit", month: "short" })} ${clock}`
    : clock
}

/** Wraps the search term in <mark>, case-insensitively. Text, never HTML. */
function Highlighted({ text, term }: { text: string; term: string }) {
  const needle = term.trim()
  if (!needle) return <>{text}</>
  const escaped = needle.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")
  const parts = text.split(new RegExp(`(${escaped})`, "gi"))
  return (
    <>
      {parts.map((part, i) =>
        part.toLowerCase() === needle.toLowerCase() ? (
          <mark
            key={i}
            className="rounded-[3px] bg-[var(--chart-4)]/35 px-0.5 text-inherit"
          >
            {part}
          </mark>
        ) : (
          <span key={i}>{part}</span>
        )
      )}
    </>
  )
}

/** Longer than this, or more than three lines, and the message is clamped. */
const CLAMP_CHARS = 240
const CLAMP_LINES = 3

function LogRow({
  group,
  range,
  term,
  large,
}: {
  group: LogGroup
  range: LogRange
  term: string
  /** The full-size panel: bigger type and a roomier row. */
  large: boolean
}) {
  const { entry, parsed, count, firstAt } = group
  const [open, setOpen] = useState(false)
  const [copied, setCopied] = useState(false)

  useEffect(() => {
    if (!copied) return
    const id = setTimeout(() => setCopied(false), 1500)
    return () => clearTimeout(id)
  }, [copied])

  const level = entry.level ?? ""
  const colour = LEVEL_COLOUR[level]
  const long =
    parsed.message.length > CLAMP_CHARS ||
    parsed.message.split("\n").length > CLAMP_LINES
  // A multi-line message is a stack trace or a SQL statement, where column
  // alignment is the structure; a one-liner reads better in the proportional
  // face. So the face follows the content.
  const structured =
    parsed.message.includes("\n") || /^\s+at\s/m.test(parsed.message)

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(entry.line)
      setCopied(true)
    } catch {
      // Clipboard can be blocked (insecure origin); nothing useful to do.
    }
  }

  const minutes = Math.round((entry.at - firstAt) / 60_000)
  const badgeColour = colour ?? "var(--muted-foreground)"

  return (
    <li
      className={cn(
        "group relative border-b border-border/70 pr-3 pl-3 last:border-0",
        large ? "py-2.5" : "py-2"
      )}
      style={
        colour
          ? {
              boxShadow: `inset 3px 0 0 ${colour}`,
              background: `color-mix(in srgb, ${colour} 6%, transparent)`,
            }
          : undefined
      }
    >
      <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5">
        <time
          dateTime={new Date(entry.at).toISOString()}
          className="text-[11.5px] text-muted-foreground tabular-nums"
        >
          {time(entry.at, range)}
        </time>
        {level && (
          <span
            className="inline-block min-w-[46px] rounded px-1.5 py-px text-center text-[10.5px] font-semibold tracking-[0.03em]"
            style={{
              color: badgeColour,
              background: `color-mix(in srgb, ${badgeColour} 14%, transparent)`,
            }}
          >
            {level}
          </span>
        )}
        {parsed.logger && (
          <span
            className="min-w-0 truncate text-[11.5px] font-medium text-muted-foreground"
            title={parsed.loggerFull ?? undefined}
          >
            {parsed.logger}
          </span>
        )}
        {count > 1 && (
          <span
            className="rounded-full bg-foreground/10 px-1.5 py-px text-[10.5px] font-semibold tabular-nums"
            title={`${count} identical lines, from ${time(firstAt, range)} to ${time(entry.at, range)}`}
          >
            ×{count}
            {minutes >= 1 && (
              <span className="ml-1 font-normal text-muted-foreground">
                over {minutes} min
              </span>
            )}
          </span>
        )}
        <button
          type="button"
          onClick={copy}
          aria-label="Copy this line"
          className="ml-auto rounded p-1 text-muted-foreground opacity-0 transition-opacity group-hover:opacity-100 hover:bg-muted hover:text-foreground focus-visible:opacity-100"
        >
          {copied ? <Check size={12} /> : <Copy size={12} />}
        </button>
      </div>

      <p
        className={cn(
          "mt-1 break-words whitespace-pre-wrap text-foreground",
          structured
            ? // tab-size 2: PostgreSQL indents a logged statement with tabs, and
              // the browser default of 8 pushes the whole query into the
              // middle of a narrow panel.
              large
              ? "font-mono text-[12.5px] leading-[1.6] [tab-size:2]"
              : "font-mono text-[11.5px] leading-[1.55] [tab-size:2]"
            : large
              ? "text-[13.5px] leading-[1.55]"
              : "text-[12.5px] leading-[1.5]",
          // The large panel has the room to show more before clamping.
          long && !open && (large ? "line-clamp-6" : "line-clamp-3")
        )}
      >
        <Highlighted text={parsed.message} term={term} />
      </p>

      {long && (
        <button
          type="button"
          onClick={() => setOpen((value) => !value)}
          aria-expanded={open}
          className="mt-1 inline-flex items-center gap-0.5 text-[11px] font-medium text-muted-foreground hover:text-foreground"
        >
          <ChevronDown
            size={11}
            className={cn("transition-transform", open && "rotate-180")}
          />
          {open ? "Show less" : "Show more"}
        </button>
      )}
    </li>
  )
}

/**
 * One service's recent logs: level filter, text search, time window.
 *
 * Mounted only when a service is opened, so it costs nothing until someone
 * asks - the query is not even enabled before that.
 *
 * Auto-refresh is on by default because the usual reason to open this is that
 * something just went wrong, and a stale log is worse than none. It pauses
 * while the person is reading (the button) since new lines arriving under
 * the cursor make a trace impossible to follow.
 */
export function ServiceLogs({
  serviceId,
  serviceLabel,
  large = false,
}: {
  serviceId: string
  /** Shown in the large panel's title; falls back to the id. */
  serviceLabel?: string
  /** Fill the space it is given, with bigger type and more lines. */
  large?: boolean
}) {
  const [expanded, setExpanded] = useState(false)
  const [level, setLevel] = useState<LogLevel | undefined>(undefined)
  const [range, setRange] = useState<LogRange>("1h")
  const [draft, setDraft] = useState("")
  const [search, setSearch] = useState("")
  const [live, setLive] = useState(true)

  // Debounced: a query per keystroke would send a request for "t", "ti",
  // "tim" and "time" in the time it takes to type one word.
  useEffect(() => {
    const id = setTimeout(() => setSearch(draft), 350)
    return () => clearTimeout(id)
  }, [draft])

  const logs = useQuery({
    queryKey: ["monitoring", "logs", serviceId, level, range, search, large],
    queryFn: () =>
      fetchServiceLogs({
        service: serviceId,
        level,
        range,
        search,
        // 500 rather than the 1,000 the endpoint allows: past a few hundred
        // rows nobody is reading, they are scrolling, and a smaller page
        // refreshes faster every ten seconds.
        limit: large ? 500 : 200,
      }),
    refetchInterval: live ? 10_000 : false,
    // Keep the lines on screen while a new filter loads, instead of blanking
    // to a spinner on every keystroke.
    placeholderData: (previous) => previous,
  })

  const groups = logs.data ? groupLines(logs.data.lines) : []

  return (
    <div
      className={cn(
        large
          ? "flex h-full min-h-0 flex-col"
          : "mt-4 border-t border-border pt-3"
      )}
    >
      <div className="mb-2 flex flex-wrap items-center gap-2">
        <p className={cn("font-medium", large ? "text-[13px]" : "text-[12px]")}>
          Logs
        </p>

        <div
          role="radiogroup"
          aria-label="Log level"
          className="flex items-center gap-0.5 rounded-md border border-border p-0.5"
        >
          {([undefined, ...LOG_LEVELS] as (LogLevel | undefined)[]).map(
            (option) => (
              <button
                key={option ?? "all"}
                type="button"
                role="radio"
                aria-checked={level === option}
                onClick={() => setLevel(option)}
                className={cn(
                  "rounded px-1.5 py-0.5 text-[11px] font-medium",
                  level === option
                    ? "bg-primary text-primary-foreground"
                    : "text-muted-foreground hover:bg-muted hover:text-foreground"
                )}
              >
                {option ?? "All"}
              </button>
            )
          )}
        </div>

        <select
          value={range}
          onChange={(event) => setRange(event.target.value as LogRange)}
          aria-label="Time window"
          className="h-6 rounded-md border border-border bg-card px-1.5 text-[11px]"
        >
          {LOG_RANGES.map((option) => (
            <option key={option} value={option}>
              Last {option}
            </option>
          ))}
        </select>

        <button
          type="button"
          onClick={() => setLive((value) => !value)}
          aria-pressed={live}
          title={live ? "Pause updates" : "Resume updates"}
          className="ml-auto inline-flex items-center gap-1 rounded-md border border-border px-1.5 py-0.5 text-[11px] text-muted-foreground hover:bg-muted hover:text-foreground"
        >
          {live ? <Pause size={11} /> : <Play size={11} />}
          {live ? "Live" : "Paused"}
        </button>
        {!large && (
          <button
            type="button"
            onClick={() => setExpanded(true)}
            title="Open in a large panel"
            className="inline-flex items-center gap-1 rounded-md border border-border px-1.5 py-0.5 text-[11px] text-muted-foreground hover:bg-muted hover:text-foreground"
          >
            <Maximize2 size={11} />
            Expand
          </button>
        )}
      </div>

      <div className="relative mb-2">
        <Search
          size={12}
          aria-hidden="true"
          className="absolute top-1/2 left-2 -translate-y-1/2 text-muted-foreground"
        />
        <input
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          placeholder="Search these logs"
          aria-label="Search logs"
          maxLength={200}
          className={cn(
            "w-full rounded-md border border-border bg-card pr-2 pl-7",
            large ? "h-8 text-[13px]" : "h-7 text-[12px]"
          )}
        />
      </div>

      {logs.isPending ? (
        <p className="py-6 text-center text-[12px] text-muted-foreground">
          Reading logs…
        </p>
      ) : logs.isError ? (
        <p className="py-4 text-[12px] text-[var(--status-bad)]">
          {friendlyMessage(logs.error, "Could not load the logs.")}
        </p>
      ) : !logs.data.reachable ? (
        <p className="rounded-md border border-border bg-muted/40 px-3 py-3 text-[12px] text-muted-foreground">
          {logs.data.error ?? "The log store did not answer."}
        </p>
      ) : logs.data.lines.length === 0 ? (
        <p className="py-6 text-center text-[12px] text-muted-foreground">
          No log lines match in this window.
        </p>
      ) : (
        <>
          <ol
            className={cn(
              "overflow-auto rounded-md border border-border bg-card",
              // Large: take whatever height is left under the controls, so
              // the list is as tall as the panel, not a fixed box inside it.
              large ? "min-h-0 flex-1" : "max-h-[440px]"
            )}
            aria-label="Log lines, newest first"
          >
            {groups.map((group) => (
              <LogRow
                key={group.key}
                group={group}
                range={range}
                term={search}
                large={large}
              />
            ))}
          </ol>
          <p className="mt-1.5 text-[11px] text-muted-foreground">
            {logs.data.lines.length} line
            {logs.data.lines.length === 1 ? "" : "s"}
            {groups.length < logs.data.lines.length &&
              `, shown as ${groups.length} (repeats grouped)`}
            , newest first
            {logs.data.truncated &&
              " · more match than are shown — narrow the filter"}
          </p>
        </>
      )}

      {expanded && (
        <LogsModal
          serviceId={serviceId}
          serviceLabel={serviceLabel ?? serviceId}
          onClose={() => setExpanded(false)}
        />
      )}
    </div>
  )
}

/**
 * The same logs in a near-full-screen panel.
 *
 * Portalled to <body> because the small view lives inside the service
 * details, which is itself inside a modal with its own overflow and
 * transform - a fixed-position child there would be clipped to it, and a
 * panel meant to be big would come out the size of its parent.
 */
function LogsModal({
  serviceId,
  serviceLabel,
  onClose,
}: {
  serviceId: string
  serviceLabel: string
  onClose: () => void
}) {
  const panelRef = useRef<HTMLDivElement>(null)
  const closeRef = useRef(onClose)
  const [shown, setShown] = useState(false)

  useEffect(() => {
    closeRef.current = onClose
  }, [onClose])

  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null
    const overflow = document.body.style.overflow
    document.body.style.overflow = "hidden"
    panelRef.current?.focus()
    const raf = requestAnimationFrame(() => setShown(true))
    // Capture phase on window, so Escape closes THIS panel and stops there.
    // The service-details modal underneath listens on document; without
    // this, one Escape would close both and drop the person back at the
    // page rather than at the details they came from.
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.stopPropagation()
        closeRef.current()
      }
    }
    window.addEventListener("keydown", onKey, true)
    return () => {
      cancelAnimationFrame(raf)
      window.removeEventListener("keydown", onKey, true)
      document.body.style.overflow = overflow
      previous?.focus?.()
    }
  }, [])

  return createPortal(
    <div className="fixed inset-0 z-[60] flex items-center justify-center p-0 sm:p-5">
      <div
        aria-hidden="true"
        onClick={onClose}
        className={cn(
          "absolute inset-0 bg-black/60 transition-opacity duration-200 motion-reduce:transition-none",
          shown ? "opacity-100" : "opacity-0"
        )}
      />
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-label={`${serviceLabel} logs`}
        tabIndex={-1}
        className={cn(
          "relative flex h-full w-full max-w-[1500px] flex-col overflow-hidden border border-border bg-card shadow-2xl outline-none sm:h-[94vh] sm:rounded-2xl",
          "transition-[opacity,transform] duration-200 ease-out motion-reduce:transition-none",
          shown ? "translate-y-0 opacity-100" : "translate-y-3 opacity-0"
        )}
      >
        <div className="flex items-center gap-3 border-b border-border px-5 py-3.5">
          <div className="min-w-0 flex-1">
            <h2 className="truncate text-[16px] font-semibold">
              {serviceLabel}
            </h2>
            <p className="text-[12px] text-muted-foreground">
              Logs · <span className="font-mono">{serviceId}</span>
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close logs"
            className="text-muted-foreground hover:bg-muted hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none inline-flex size-8 items-center justify-center rounded-btn"
          >
            <X size={18} />
          </button>
        </div>
        <div className="min-h-0 flex-1 p-5">
          <ServiceLogs serviceId={serviceId} large />
        </div>
      </div>
    </div>,
    document.body
  )
}
