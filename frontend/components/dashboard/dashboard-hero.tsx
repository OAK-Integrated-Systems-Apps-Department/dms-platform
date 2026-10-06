"use client"

import type { CSSProperties } from "react"
import { Loader2 } from "lucide-react"

import { useNow } from "@/components/dashboard/motion"
import { DASHBOARD_PERIODS, type DashboardPeriod } from "@/lib/dashboard/api"

/** The sheets in the floating stack, bottom to top - the kinds of file people keep here. */
const SHEETS = [
  { label: "PPTX", color: "#c43e1c" },
  { label: "XLSX", color: "#217346" },
  { label: "DOCX", color: "#2b579a" },
  { label: "PDF", color: "#d13438" },
]

function greeting(hour: number): string {
  if (hour < 12) return "Good morning"
  if (hour < 17) return "Good afternoon"
  return "Good evening"
}

/**
 * The top of the dashboard: the date and a greeting on the user's own clock,
 * a one-line summary from the live figures, the period every figure below
 * covers, and a stack of documents floating in 3D.
 */
export function DashboardHero({
  firstName,
  isLoadingName,
  summary,
  period,
  onPeriodChange,
  refreshing,
}: {
  firstName: string
  isLoadingName?: boolean
  /** The live line under the greeting; absent while the figures load. */
  summary?: string
  period: DashboardPeriod
  onPeriodChange: (period: DashboardPeriod) => void
  /** New figures are on their way (after switching period). */
  refreshing?: boolean
}) {
  const now = useNow()
  const index = DASHBOARD_PERIODS.indexOf(period)

  return (
    <section
      style={{ "--dash-order": 0 } as CSSProperties}
      className="dash-rise dash-hero mb-6 rounded-2xl border border-border px-6 py-6 shadow-[var(--ledger-shadow)] sm:px-8"
    >
      <span
        aria-hidden="true"
        className="dash-orb -top-24 right-40 size-72"
        style={{ background: "var(--dash-glow)" }}
      />
      <span
        aria-hidden="true"
        className="dash-orb -bottom-28 left-1/3 size-64"
        style={{ background: "var(--dash-gold-glow)", animationDelay: "-6s" }}
      />

      <div className="flex items-center justify-between gap-8">
        <div className="min-w-0">
          <div className="mb-1.5 h-4 text-[11.5px] font-semibold tracking-[0.12em] text-primary uppercase">
            {now?.toLocaleDateString(undefined, {
              weekday: "long",
              day: "numeric",
              month: "long",
              year: "numeric",
            })}
          </div>
          <h1 className="flex items-center gap-2.5 font-heading text-[28px] leading-tight font-semibold tracking-[-0.01em] sm:text-[32px]">
            {now ? greeting(now.getHours()) : "Welcome back"}
            {isLoadingName ? (
              <Loader2
                size={20}
                className="animate-spin text-muted-foreground"
              />
            ) : (
              firstName && `, ${firstName}`
            )}
          </h1>
          <p className="mt-2 flex min-h-5 items-center gap-2 text-[13.5px] text-muted-foreground">
            {summary && (
              <>
                <span
                  aria-hidden="true"
                  className="dash-ping relative inline-block size-2 shrink-0 rounded-full bg-primary"
                />
                {summary}
              </>
            )}
          </p>

          <div className="mt-5 flex items-center gap-3">
            <div
              role="radiogroup"
              aria-label="Period"
              className="relative grid grid-cols-3 rounded-xl border border-border bg-card/80 p-1 shadow-[inset_0_1px_2px_rgba(0,0,0,0.05)] backdrop-blur"
            >
              {/* The highlight slides to the chosen period. */}
              <span
                aria-hidden="true"
                className="absolute top-1 bottom-1 left-1 rounded-lg bg-primary shadow-[0_2px_0_var(--dash-depth),0_6px_14px_-6px_var(--dash-glow)] transition-transform duration-300 ease-[cubic-bezier(0.2,0.7,0.2,1)]"
                style={{
                  width: "calc((100% - 0.5rem) / 3)",
                  transform: `translateX(${index * 100}%)`,
                }}
              />
              {DASHBOARD_PERIODS.map((days) => (
                <button
                  key={days}
                  type="button"
                  role="radio"
                  aria-checked={days === period}
                  onClick={() => onPeriodChange(days)}
                  className={`relative z-10 rounded-lg px-3.5 py-1.5 text-[12.5px] font-semibold whitespace-nowrap transition-colors ${
                    days === period
                      ? "text-primary-foreground"
                      : "text-muted-foreground hover:text-foreground"
                  }`}
                >
                  {days} days
                </button>
              ))}
            </div>
            {refreshing && (
              <Loader2
                size={16}
                aria-label="Updating"
                className="animate-spin text-muted-foreground"
              />
            )}
          </div>
        </div>

        <DocumentStack />
      </div>
    </section>
  )
}

/** Four documents floating over each other, tipped back in 3D - decoration only. */
function DocumentStack() {
  return (
    <div
      aria-hidden="true"
      className="dash-stack-scene mr-6 hidden h-[160px] w-[230px] shrink-0 items-center justify-center md:flex"
    >
      <div className="dash-stack h-[150px] w-[118px]">
        {SHEETS.map((sheet, layer) => (
          <div
            key={sheet.label}
            className="dash-sheet border border-border bg-card"
            style={{ "--layer": layer } as CSSProperties}
          >
            <div
              className="absolute top-0 left-0 h-[5px] w-full rounded-t-[10px]"
              style={{ background: sheet.color }}
            />
            <div className="flex flex-col gap-[7px] px-3 pt-4">
              <span
                className="mb-1 w-fit rounded-[4px] px-1.5 py-px text-[8.5px] font-bold tracking-wide text-white"
                style={{ background: sheet.color }}
              >
                {sheet.label}
              </span>
              {[88, 72, 80, 56, 68].map((width, line) => (
                <span
                  key={line}
                  className="block h-[4px] rounded-full bg-muted-foreground/20"
                  style={{ width: `${width}%` }}
                />
              ))}
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}
