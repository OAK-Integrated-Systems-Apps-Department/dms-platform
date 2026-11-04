"use client"

import { useEffect, useState } from "react"
import {
  Check,
  CircleCheck,
  CircleHelp,
  CircleMinus,
  CircleX,
  Copy,
  X,
} from "lucide-react"

import { cn } from "@/lib/utils"
import type { Dependency, DependencyHealth } from "@/lib/monitoring/api"

/**
 * Actuator's four states, kept as four rather than reduced to up/down.
 *
 * OUT_OF_SERVICE is a deliberate state - something was taken out on
 * purpose - and showing it as "Down" would read as a fault and send
 * somebody looking for a cause that does not exist. UNKNOWN is likewise its
 * own answer: the indicator ran and could not tell.
 *
 * Each state differs in more than colour: its own icon, its own ring shape
 * (full, dashed, partial) and its own word, so nothing here depends on
 * telling the colours apart.
 */
type StateKey = "UP" | "DOWN" | "OUT_OF_SERVICE" | "UNKNOWN"

const STATES: Record<
  StateKey,
  {
    label: string
    /** Lower-case form for the header summary. */
    phrase: string
    colour: string
    icon: typeof CircleCheck
    bad: boolean
    /** Ring: how much of the circle is drawn, and with what dash pattern. */
    ring: { length: number; pattern?: string }
  }
> = {
  DOWN: {
    label: "Failing",
    phrase: "failing",
    colour: "var(--status-bad)",
    icon: CircleX,
    bad: true,
    ring: { length: 100 },
  },
  OUT_OF_SERVICE: {
    label: "Out of service",
    phrase: "out of service",
    colour: "var(--gold-foreground)",
    icon: CircleMinus,
    bad: true,
    ring: { length: 100, pattern: "6 4" },
  },
  UNKNOWN: {
    label: "Unknown",
    phrase: "unknown",
    colour: "var(--ink-faint)",
    icon: CircleHelp,
    bad: false,
    ring: { length: 30 },
  },
  UP: {
    label: "Healthy",
    phrase: "healthy",
    colour: "var(--status-good)",
    icon: CircleCheck,
    bad: false,
    ring: { length: 100 },
  },
}

/** Problems first, so a fault is never below a row of healthy green. */
const ORDER: StateKey[] = ["DOWN", "OUT_OF_SERVICE", "UNKNOWN", "UP"]

function keyOf(status: string): StateKey {
  return status in STATES ? (status as StateKey) : "UNKNOWN"
}

const tint = (colour: string, pct: number) =>
  `color-mix(in srgb, ${colour} ${pct}%, transparent)`

/** A ring that draws itself in on mount, with the state icon at its centre. */
function StateRing({ status, index }: { status: string; index: number }) {
  const state = STATES[keyOf(status)]
  const Icon = state.icon
  const [on, setOn] = useState(false)

  useEffect(() => {
    const id = setTimeout(() => setOn(true), 60 + index * 60)
    return () => clearTimeout(id)
  }, [index])

  const { length, pattern } = state.ring

  return (
    <div className="relative size-[60px]">
      <svg viewBox="0 0 60 60" className="size-full -rotate-90" aria-hidden="true">
        <circle
          cx={30}
          cy={30}
          r={25}
          fill="none"
          stroke="var(--muted)"
          strokeWidth={5}
        />
        <circle
          cx={30}
          cy={30}
          r={25}
          pathLength={100}
          fill="none"
          stroke={state.colour}
          strokeWidth={5}
          strokeLinecap={pattern ? "butt" : "round"}
          strokeDasharray={pattern ?? `${on ? length : 0} 100`}
          className={cn(
            "transition-[stroke-dasharray,opacity] duration-700 ease-out motion-reduce:transition-none",
            pattern && (on ? "opacity-100" : "opacity-0")
          )}
        />
      </svg>
      <Icon
        size={22}
        aria-hidden="true"
        className="absolute inset-0 m-auto"
        style={{ color: state.colour }}
      />
    </div>
  )
}

function DependencyTile({
  dependency,
  index,
  selected,
  onSelect,
}: {
  dependency: Dependency
  index: number
  selected: boolean
  onSelect: () => void
}) {
  const state = STATES[keyOf(dependency.status)]

  return (
    <li>
      <button
        type="button"
        onClick={onSelect}
        aria-pressed={selected}
        className={cn(
          "flex w-full flex-col items-center gap-2 rounded-lg border px-2 py-3.5 transition-[border-color,box-shadow,transform] duration-200",
          "hover:-translate-y-0.5 hover:shadow-md focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none",
          "motion-reduce:transition-none motion-reduce:hover:translate-y-0",
          state.bad
            ? "border-[var(--status-bad)] bg-[var(--rust-tint)]"
            : "border-border bg-card hover:border-foreground/30",
          selected && "ring-2 ring-foreground/60"
        )}
      >
        <StateRing status={dependency.status} index={index} />
        <span
          className="w-full truncate text-center text-[12.5px] font-medium"
          title={dependency.label}
        >
          {dependency.label}
        </span>
        <span
          className="text-[11.5px] font-semibold"
          style={{ color: state.colour }}
        >
          {state.label}
        </span>
      </button>
    </li>
  )
}

function DependencyDetail({
  dependency,
  onClose,
}: {
  dependency: Dependency
  onClose: () => void
}) {
  const state = STATES[keyOf(dependency.status)]
  const Icon = state.icon
  const [copied, setCopied] = useState(false)

  useEffect(() => {
    if (!copied) return
    const id = setTimeout(() => setCopied(false), 1500)
    return () => clearTimeout(id)
  }, [copied])

  const copy = async () => {
    if (!dependency.detail) return
    try {
      await navigator.clipboard.writeText(dependency.detail)
      setCopied(true)
    } catch {
      // Clipboard can be blocked (insecure origin, permissions); nothing to do.
    }
  }

  return (
    <div
      role="region"
      aria-label={`Details for ${dependency.label}`}
      className="mx-4 mb-4 rounded-lg border border-border p-4"
      style={{ background: tint(state.colour, 6) }}
    >
      <div className="flex items-center gap-2">
        <Icon size={15} style={{ color: state.colour }} aria-hidden="true" />
        <p className="min-w-0 flex-1 truncate text-[13.5px] font-semibold">
          {dependency.label}
          <span
            className="ml-2 text-[12px] font-semibold"
            style={{ color: state.colour }}
          >
            {state.label}
          </span>
        </p>
        <button
          type="button"
          onClick={onClose}
          aria-label="Close details"
          className="rounded p-0.5 text-muted-foreground hover:bg-muted hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
        >
          <X size={14} />
        </button>
      </div>
      {dependency.detail ? (
        <>
          <p className="mt-2 text-[12.5px] leading-[1.6] break-words text-muted-foreground">
            {dependency.detail}
          </p>
          <button
            type="button"
            onClick={copy}
            className="mt-2 inline-flex items-center gap-1 text-[11.5px] text-muted-foreground hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
          >
            {copied ? <Check size={11} /> : <Copy size={11} />}
            {copied ? "Copied" : "Copy message"}
          </button>
        </>
      ) : (
        <p className="mt-2 text-[12.5px] text-muted-foreground">
          The application reported no further detail.
        </p>
      )}
    </div>
  )
}

/**
 * The backend's own view of what it depends on.
 *
 * Sits beside the Services board rather than replacing it, because the two
 * answer different questions and the gap between them is where real faults
 * hide. Services asks whether a port is listening. This asks whether a
 * query from our own pool, with our own credentials, actually came back.
 * A Postgres that is running but rejecting this app's password is green on
 * one board and red on the other - and the red one is right.
 */
export function DependencyHealthCard({
  health,
  className,
}: {
  health: DependencyHealth
  className?: string
}) {
  // undefined = nobody has chosen yet (a problem with a message opens by
  // itself); null = the person closed the panel on purpose.
  const [picked, setPicked] = useState<string | null | undefined>(undefined)

  const sorted = [...health.dependencies].sort(
    (a, b) => ORDER.indexOf(keyOf(a.status)) - ORDER.indexOf(keyOf(b.status))
  )

  const counts = ORDER.map((key) => ({
    key,
    count: health.dependencies.filter((d) => keyOf(d.status) === key).length,
  })).filter((c) => c.count > 0)

  const problems = counts.filter((c) => c.key !== "UP")
  const worst = problems[0]?.key ?? keyOf(health.status)
  const SummaryIcon = STATES[worst].icon
  const summary =
    problems.length === 0
      ? health.dependencies.length > 0 && worst === "UP"
        ? "All healthy"
        : STATES[worst].label
      : problems.map((c) => `${c.count} ${STATES[c.key].phrase}`).join(" · ")

  const defaultId = sorted.find(
    (d) => STATES[keyOf(d.status)].bad && d.detail
  )?.id
  const selectedId = picked === undefined ? defaultId : picked
  const selected =
    sorted.find((d) => d.id === selectedId) ?? null

  return (
    <div className={cn("rounded-lg border border-border bg-card", className)}>
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-border px-4 py-3">
        <div>
          <h2 className="text-[14px] font-semibold">Backend dependencies</h2>
          <p className="text-[12px] text-muted-foreground">
            What the application itself reports, not what is merely reachable.
          </p>
        </div>
        <span
          className="inline-flex h-6 items-center justify-center gap-1 rounded-btn px-2.5 text-[12px] font-semibold whitespace-nowrap"
          style={{
            color: STATES[worst].colour,
            background: tint(STATES[worst].colour, 12),
          }}
          role="status"
        >
          <SummaryIcon size={14} aria-hidden="true" />
          {summary}
        </span>
      </div>

      {health.dependencies.length === 0 ? (
        <p className="px-4 py-8 text-center text-[13px] text-muted-foreground">
          No health indicators are registered.
        </p>
      ) : (
        <ul className="grid grid-cols-[repeat(auto-fill,minmax(132px,1fr))] gap-2.5 p-4">
          {sorted.map((dependency, i) => (
            <DependencyTile
              key={dependency.id}
              dependency={dependency}
              index={i}
              selected={dependency.id === selectedId}
              onSelect={() =>
                setPicked(dependency.id === selectedId ? null : dependency.id)
              }
            />
          ))}
        </ul>
      )}

      {selected && (
        <DependencyDetail
          dependency={selected}
          onClose={() => setPicked(null)}
        />
      )}
    </div>
  )
}