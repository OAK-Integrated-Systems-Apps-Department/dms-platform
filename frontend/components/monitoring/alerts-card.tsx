"use client"

import { useState } from "react"
import {
  AlertTriangle,
  BellOff,
  BellRing,
  ChevronDown,
  EyeOff,
  CircleCheck,
  CircleX,
  Clock,
} from "lucide-react"

import { useErrorToast } from "@/lib/use-error-toast"
import { cn } from "@/lib/utils"
import type { AlertRule, AlertsResponse } from "@/lib/monitoring/api"

/**
 * "12 min", "3 h 05 min", "2 d" - how long, not a clock time. When something
 * is wrong the question is how long it has been wrong, and subtracting two
 * timestamps in your head during an incident is a bad use of the moment.
 */
function ago(epochSeconds: number): string {
  const minutes = Math.max(0, Math.round((Date.now() / 1000 - epochSeconds) / 60))
  if (minutes < 1) return "just now"
  if (minutes < 60) return `${minutes} min`
  const hours = Math.floor(minutes / 60)
  if (hours < 24)
    return `${hours} h${minutes % 60 ? ` ${String(minutes % 60).padStart(2, "0")} min` : ""}`
  return `${Math.floor(hours / 24)} d`
}

function clock(epochSeconds: number): string {
  return new Date(epochSeconds * 1000).toLocaleString(undefined, {
    day: "2-digit",
    month: "short",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  })
}

/** "ApiErrorRateHigh" -> "Api Error Rate High". The rule names are identifiers. */
function readable(name: string): string {
  return name.replace(/([a-z0-9])([A-Z])/g, "$1 $2")
}

type Tone = { colour: string; label: string; icon: typeof CircleX }

/**
 * Every state is its own icon AND its own word, as well as a colour. The
 * three that matter most are deliberately unlike each other in shape: a
 * filled alarm (firing), a clock (pending - true, but not for long enough
 * yet), and a cross (broken - cannot run at all).
 */
function toneOf(rule: AlertRule): Tone {
  if (rule.broken)
    return { colour: "var(--status-bad)", label: "Rule broken", icon: CircleX }
  if (rule.state === "firing")
    return rule.severity === "critical"
      ? { colour: "var(--status-bad)", label: "Critical", icon: BellRing }
      : { colour: "var(--status-warn)", label: "Warning", icon: BellRing }
  if (rule.state === "pending")
    return { colour: "var(--status-warn)", label: "Pending", icon: Clock }
  return { colour: "var(--status-good)", label: "OK", icon: CircleCheck }
}

const tint = (colour: string, pct: number) =>
  `color-mix(in srgb, ${colour} ${pct}%, transparent)`

function ActiveRow({ rule }: { rule: AlertRule }) {
  const tone = toneOf(rule)
  const Icon = tone.icon

  return (
    <li
      className="flex gap-3 px-4 py-3"
      style={{
        boxShadow: `inset 3px 0 0 ${tone.colour}`,
        background: tint(tone.colour, 7),
      }}
    >
      <Icon
        size={17}
        className="mt-0.5 shrink-0"
        style={{ color: tone.colour }}
        aria-hidden="true"
      />
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5">
          <p className="text-[13.5px] font-semibold">{readable(rule.name)}</p>
          <span
            className="rounded px-1.5 py-px text-[10.5px] font-semibold tracking-[0.03em] uppercase"
            style={{ color: tone.colour, background: tint(tone.colour, 14) }}
          >
            {tone.label}
          </span>
          {rule.component && (
            <span className="text-[11.5px] text-muted-foreground">
              {rule.component}
            </span>
          )}
        </div>
        {/* The rendered description has the actual value in it - "7.2% are
            returning 5xx" - which is what the summary cannot say. */}
        <p className="mt-0.5 text-[12.5px] leading-[1.5]">
          {rule.detail ?? rule.summary ?? "No description."}
        </p>
        {rule.broken && (
          <p className="mt-0.5 text-[12px] text-muted-foreground">
            Prometheus could not evaluate this rule, so it cannot fire. Check
            its expression in alerts.yml.
          </p>
        )}
        {rule.since !== null && (
          <p
            className="mt-1 text-[11.5px] text-muted-foreground tabular-nums"
            title={`First true at ${clock(rule.since)}`}
          >
            {rule.state === "pending" ? "True for " : "Firing for "}
            {ago(rule.since)} · since {clock(rule.since)}
          </p>
        )}
      </div>
    </li>
  )
}

/**
 * What is firing, and the rest of the rules for reference.
 *
 * This is the only place an alert is visible at all. The rules are
 * evaluated by Prometheus and nothing delivers them: no Alertmanager, no
 * email or chat route, and Grafana's alerting screen is not exposed. So the
 * card says plainly when nothing is firing, and says just as plainly when it
 * could not ask - because "I could not check" must never read as "all clear".
 */
export function AlertsCard({
  alerts,
  className,
}: {
  alerts: AlertsResponse
  className?: string
}) {
  const [showQuiet, setShowQuiet] = useState(false)
  useErrorToast(
    alerts.reachable ? null : (alerts.error ?? "Whether anything is firing is unknown, not clear."),
    "Could not read the alerts."
  )

  const active = alerts.rules.filter(
    (rule) => rule.broken || rule.state === "firing" || rule.state === "pending"
  )
  const quiet = alerts.rules.filter((rule) => !active.includes(rule))
  // Quiet rules that cannot see their input. Kept separate from the genuinely
  // quiet ones: "not firing" and "cannot fire" must not share a count.
  const blind = quiet.filter((rule) => rule.blind)
  const firing = active.filter((rule) => rule.state === "firing" && !rule.broken)
  const pending = active.filter((rule) => rule.state === "pending" && !rule.broken)
  const broken = active.filter((rule) => rule.broken)
  const critical = firing.some((rule) => rule.severity === "critical") || broken.length > 0

  const blindNote =
    blind.length > 0
      ? `${blind.length} can't see ${blind.length === 1 ? "its" : "their"} data`
      : null

  const headline = !alerts.reachable
    ? "Unknown"
    : active.length === 0
      ? ["Nothing firing", blindNote].filter(Boolean).join(" · ")
      : [
          firing.length > 0 && `${firing.length} firing`,
          pending.length > 0 && `${pending.length} pending`,
          broken.length > 0 && `${broken.length} broken`,
          blindNote,
        ]
          .filter(Boolean)
          .join(" · ")

  const colour = !alerts.reachable
    ? "var(--ink-faint)"
    : active.length === 0
      ? blind.length > 0
        ? // Nothing firing, but some of what is watched cannot be seen. Green
          // here would say more than is known.
          "var(--status-warn)"
        : "var(--status-good)"
      : critical
        ? "var(--status-bad)"
        : "var(--status-warn)"
  const HeadIcon = !alerts.reachable
    ? AlertTriangle
    : active.length === 0
      ? BellOff
      : BellRing

  return (
    <div
      className={cn(
        "overflow-hidden rounded-xl border border-border bg-card",
        className
      )}
    >
      <div className="flex flex-wrap items-center gap-3 px-4 py-3">
        <span
          className="flex size-9 shrink-0 items-center justify-center rounded-full"
          style={{ color: colour, background: tint(colour, 14) }}
          aria-hidden="true"
        >
          <HeadIcon size={18} />
        </span>
        <div className="min-w-0 flex-1">
          <h2 className="text-[14px] font-semibold">Alerts</h2>
          <p className="text-[12px] text-muted-foreground">
            {alerts.reachable
              ? `${alerts.rules.length} rules are watched. Nothing else delivers them, so this is where a firing one shows.`
              : "Prometheus did not answer."}
          </p>
        </div>
        <span
          className="text-[13px] font-semibold"
          style={{ color: colour }}
          role="status"
        >
          {headline}
        </span>
      </div>

      {!alerts.reachable ? (
        null
      ) : (
        <>
          {active.length > 0 && (
            <ul className="divide-y divide-border border-t border-border">
              {active.map((rule) => (
                <ActiveRow key={rule.name} rule={rule} />
              ))}
            </ul>
          )}

          {quiet.length > 0 && (
            <div className="border-t border-border">
              <button
                type="button"
                onClick={() => setShowQuiet((value) => !value)}
                aria-expanded={showQuiet}
                className="flex w-full items-center gap-2 px-4 py-2.5 text-left text-[12.5px] font-medium text-muted-foreground transition-colors hover:bg-muted/50 hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none focus-visible:ring-inset"
              >
                <ChevronDown
                  size={14}
                  className={cn(
                    "transition-transform duration-200 motion-reduce:transition-none",
                    showQuiet && "rotate-180"
                  )}
                  aria-hidden="true"
                />
                {quiet.length - blind.length} other rule
                {quiet.length - blind.length === 1 ? "" : "s"}{" "}
                {active.length === 0 ? "watching, none firing" : "not firing"}
                {blind.length > 0 && ` · ${blind.length} blind`}
              </button>
              {showQuiet && (
                <ul className="grid gap-x-6 gap-y-1.5 border-t border-border px-4 py-3 sm:grid-cols-2 lg:grid-cols-3">
                  {quiet.map((rule) => (
                    <li
                      key={rule.name}
                      className="flex items-center gap-2 text-[12.5px]"
                      title={
                        rule.blind
                          ? `No data for ${rule.requires ?? "its metric"}, so this rule cannot fire.`
                          : (rule.summary ?? undefined)
                      }
                    >
                      {rule.blind ? (
                        <EyeOff
                          size={13}
                          className="shrink-0"
                          style={{ color: "var(--status-warn)" }}
                          aria-hidden="true"
                        />
                      ) : (
                        <CircleCheck
                          size={13}
                          className="shrink-0"
                          style={{ color: "var(--status-good)" }}
                          aria-hidden="true"
                        />
                      )}
                      <span className="min-w-0 truncate">
                        {readable(rule.name)}
                      </span>
                      {rule.blind ? (
                        <span
                          className="shrink-0 text-[10.5px] font-semibold uppercase"
                          style={{ color: "var(--status-warn)" }}
                        >
                          no data
                        </span>
                      ) : (
                        rule.severity === "critical" && (
                          <span className="shrink-0 text-[10.5px] font-semibold text-muted-foreground uppercase">
                            critical
                          </span>
                        )
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          )}
        </>
      )}
    </div>
  )
}
