"use client"

import { cn } from "@/lib/utils"

const SIZE = 150
const CX = SIZE / 2
const CY = SIZE / 2

/** The dial itself: thick, and the thing you actually read. */
const DIAL_RADIUS = 52
const DIAL_STROKE = 13

/** The threshold band: thin, outside the dial, and never competing with it. */
const BAND_RADIUS = 65
const BAND_STROKE = 4

/**
 * 270 degrees, open at the bottom. A full ring has no beginning, so there is
 * no way to see where "empty" is; the gap gives the scale two ends.
 */
const SWEEP = 0.75

const DIAL_C = 2 * Math.PI * DIAL_RADIUS
const BAND_C = 2 * Math.PI * BAND_RADIUS

/** Rotated so the arc starts at 7:30 and ends at 4:30, gap centred below. */
const ROTATE = `rotate(135, ${CX}, ${CY})`

export interface GaugeThresholds {
  /** Fraction at which this stops being fine. */
  warnAt: number
  /** Fraction at which it needs acting on. */
  criticalAt: number
}

function colourFor(value: number, { warnAt, criticalAt }: GaugeThresholds) {
  if (value >= criticalAt) return "var(--status-bad)"
  if (value >= warnAt) return "var(--status-warn)"
  return "var(--status-good)"
}

/** One segment of the threshold band, as a dash offset into the 270° arc. */
function Segment({
  from,
  to,
  colour,
}: {
  from: number
  to: number
  colour: string
}) {
  if (to <= from) return null
  return (
    <circle
      cx={CX}
      cy={CY}
      r={BAND_RADIUS}
      fill="none"
      stroke={colour}
      strokeWidth={BAND_STROKE}
      strokeDasharray={`${(to - from) * SWEEP * BAND_C} ${BAND_C}`}
      strokeDashoffset={-from * SWEEP * BAND_C}
      transform={ROTATE}
    />
  )
}

/**
 * A bounded reading as a dial, with its thresholds drawn around it.
 *
 * A gauge only works for something with a ceiling - the arc is a fraction
 * OF something, and "72% of the heap" has a denominator while "0.09
 * requests per second" does not. Rates and counts stay as plain figures;
 * only the six resources that can actually run out are drawn this way.
 *
 * The outer band shows where the thresholds are, so the colour of the
 * number is never the only way to know how close to trouble it is - you can
 * see the needle approaching the amber before anything changes colour.
 * Colour is never alone anyway: the figure is written out, and the band is
 * positional.
 */
export function Gauge({
  label,
  value,
  thresholds,
  className,
}: {
  label: string
  /** 0..1, or null when there is no sample - which is not the same as zero. */
  value: number | null
  thresholds: GaugeThresholds
  className?: string
}) {
  const hasValue = value !== null && Number.isFinite(value)
  // Clamped only for drawing. A pool briefly over its own maximum is real,
  // and the number below still says so.
  const drawn = hasValue ? Math.min(Math.max(value, 0), 1) : 0
  const colour = hasValue ? colourFor(value, thresholds) : "var(--ink-faint)"
  const percent = hasValue ? value * 100 : null

  return (
    <div
      className={cn(
        "flex flex-col items-center rounded-xl border border-border bg-card p-3",
        className
      )}
    >
      <svg
        viewBox={`0 0 ${SIZE} ${SIZE}`}
        className="w-full max-w-[150px]"
        role="img"
        aria-label={
          hasValue
            ? `${label}: ${percent!.toFixed(1)} percent`
            : `${label}: no data`
        }
      >
        {/* The band marks where trouble STARTS - amber, then red - and
            nothing before it. A green segment over the healthy range was
            the first attempt: it repeated what the dial already says, in
            the same hue, so the two arcs merged into one thick green
            smear. Leaving the safe range unmarked makes the warning
            legible as a warning. */}
        <Segment
          from={thresholds.warnAt}
          to={thresholds.criticalAt}
          colour="var(--status-warn)"
        />
        <Segment from={thresholds.criticalAt} to={1} colour="var(--status-bad)" />

        {/* The dial's own track - what "full" would look like. --border,
            not --muted: muted is #fbfaf6, all but invisible on a white
            card, which left the arc floating with nothing to be a
            fraction of. */}
        <circle
          cx={CX}
          cy={CY}
          r={DIAL_RADIUS}
          fill="none"
          stroke="var(--border)"
          strokeWidth={DIAL_STROKE}
          strokeDasharray={`${SWEEP * DIAL_C} ${DIAL_C}`}
          transform={ROTATE}
        />
        {hasValue && drawn > 0 && (
          <circle
            cx={CX}
            cy={CY}
            r={DIAL_RADIUS}
            fill="none"
            stroke={colour}
            strokeWidth={DIAL_STROKE}
            // butt, not round: a round cap on a near-zero value paints a
            // blob bigger than the value it stands for.
            strokeLinecap="butt"
            strokeDasharray={`${drawn * SWEEP * DIAL_C} ${DIAL_C}`}
            transform={ROTATE}
            className="transition-[stroke-dasharray] duration-500"
          />
        )}

        <text
          x={CX}
          y={CY + 6}
          textAnchor="middle"
          fill={colour}
          className="text-[21px] font-semibold tabular-nums"
        >
          {percent === null
            ? "—"
            : `${percent < 10 ? percent.toFixed(1) : Math.round(percent)}%`}
        </text>
      </svg>
      <p className="mt-0.5 text-center text-[12px] font-medium">{label}</p>
      {!hasValue && (
        <p className="text-[11px] text-muted-foreground">No data</p>
      )}
    </div>
  )
}
