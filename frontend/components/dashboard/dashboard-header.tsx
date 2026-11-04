"use client"

import type { CSSProperties } from "react"
import { Loader2 } from "lucide-react"

import { useNow } from "@/components/dashboard/motion"
import { DASHBOARD_PERIODS, type DashboardPeriod } from "@/lib/dashboard/api"
import { cn } from "@/lib/utils"

/** The sheets in the floating stack, bottom to top - the kinds of file people keep here. */
const SHEETS = [
  { label: "PPTX", color: "#c43e1c" },
  { label: "XLSX", color: "#217346" },
  { label: "DOCX", color: "#2b579a" },
  { label: "PDF", color: "#d13438" },
]

/** The fixed platform roles, in words; tenant-defined roles are shown as named. */
const ROLE_NAMES: Record<string, string> = {
  PLATFORM_ADMIN: "Platform administrator",
  TENANT_ADMIN: "Administrator",
  MEMBER: "Member",
}

function greeting(hour: number): string {
  if (hour < 12) return "Good morning"
  if (hour < 17) return "Good afternoon"
  return "Good evening"
}

/**
 * The top of the dashboard: the date and a greeting on the user's own clock,
 * who they're signed in as, a one-line summary from the live figures, and
 * the period every figure below covers. Search, notifications and the
 * account menu live in the app's top bar, on every page.
 */
export function DashboardHeader({
  name,
  role,
  isLoadingName,
  summary,
  period,
  onPeriodChange,
  refreshing,
}: {
  name: string
  /** The user's first role, as returned by /users/me (display only). */
  role?: string
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
  const firstName = name.split(" ")[0]
  const initials = name
    .split(" ")
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase())
    .join("")

  return (
    <section
      aria-label="Overview"
      style={{ "--dash-order": 0 } as CSSProperties}
      className="dash-rise dash-hero mb-5 rounded-2xl border border-border px-5 py-5 shadow-[var(--ledger-shadow)] sm:px-7"
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

      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between sm:gap-6">
        <div className="min-w-0 flex-1">
          <div className="mb-1 h-4 text-[11px] font-semibold tracking-[0.12em] text-primary uppercase">
            {now?.toLocaleDateString(undefined, {
              weekday: "long",
              day: "numeric",
              month: "long",
              year: "numeric",
            })}
          </div>
          <h1 className="flex flex-wrap items-center gap-x-2.5 font-heading text-[24px] leading-tight font-semibold tracking-[-0.01em] sm:text-[28px]">
            {now ? greeting(now.getHours()) : "Welcome back"}
            {isLoadingName ? (
              <Loader2
                size={18}
                aria-label="Loading your name"
                className="animate-spin text-muted-foreground"
              />
            ) : (
              firstName && `, ${firstName}`
            )}
          </h1>

          <div className="mt-2.5 flex flex-wrap items-center gap-x-3 gap-y-1.5 text-[13px] text-muted-foreground">
            {name && (
              <span className="inline-flex items-center gap-2">
                <span
                  aria-hidden="true"
                  className="flex size-6 items-center justify-center rounded-full bg-primary font-heading text-[10px] font-bold text-primary-foreground"
                >
                  {initials}
                </span>
                <span className="font-medium text-foreground">{name}</span>
                {role && (
                  <span className="border border-border bg-card/70 inline-flex h-6 items-center justify-center gap-1 rounded-btn px-2.5 text-[12px] font-semibold whitespace-nowrap">
                    {ROLE_NAMES[role] ?? role}
                  </span>
                )}
              </span>
            )}
            {summary && (
              <span className="inline-flex items-center gap-2">
                <span
                  aria-hidden="true"
                  className="dash-ping relative inline-block size-2 shrink-0 rounded-full bg-primary"
                />
                {summary}
              </span>
            )}
          </div>
        </div>

        <DocumentStack />

        <div className="flex shrink-0 flex-col items-start gap-1 sm:items-end sm:gap-2">
          <div
            role="radiogroup"
            aria-label="Period the figures cover"
            className="relative grid grid-cols-3 rounded-lg border border-border bg-card/80 p-1 shadow-[inset_0_1px_2px_rgba(0,0,0,0.05)] backdrop-blur"
          >
            {/* The highlight slides to the chosen period. */}
            <span
              aria-hidden="true"
              className="absolute top-1 bottom-1 left-1 rounded-lg bg-primary shadow-[0_2px_0_var(--dash-depth)] transition-transform duration-300 ease-[cubic-bezier(0.2,0.7,0.2,1)]"
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
                className={cn(
                  "relative z-10 rounded-lg px-3 py-1.5 text-[12px] font-semibold whitespace-nowrap transition-colors focus-visible:ring-2 focus-visible:ring-ring/50 focus-visible:outline-none",
                  days === period
                    ? "text-primary-foreground"
                    : "text-muted-foreground hover:text-foreground"
                )}
              >
                {days}
                <span className="hidden sm:inline"> days</span>
                <span className="sm:hidden">d</span>
              </button>
            ))}
          </div>
          <span
            aria-live="polite"
            className="flex h-4 items-center gap-1.5 text-[11px] text-muted-foreground"
          >
            {refreshing && (
              <>
                <Loader2
                  size={12}
                  className="animate-spin"
                  aria-hidden="true"
                />
                Updating…
              </>
            )}
          </span>
        </div>
      </div>
    </section>
  )
}

/** Four documents floating over each other, tipped back in 3D - decoration only. */
function DocumentStack() {
  return (
    <div
      aria-hidden="true"
      className="dash-stack-scene hidden h-[110px] w-[160px] shrink-0 items-center justify-center xl:flex"
    >
      <div className="dash-stack h-[104px] w-[82px]">
        {SHEETS.map((sheet, layer) => (
          <div
            key={sheet.label}
            className="dash-sheet border border-border bg-card"
            style={{ "--layer": layer } as CSSProperties}
          >
            <div
              className="absolute top-0 left-0 h-[4px] w-full rounded-t-[10px]"
              style={{ background: sheet.color }}
            />
            <div className="flex flex-col gap-[5px] px-2.5 pt-3">
              <span
                className="mb-0.5 w-fit rounded-[3px] px-1 py-px text-[7px] font-bold tracking-wide text-white"
                style={{ background: sheet.color }}
              >
                {sheet.label}
              </span>
              {[88, 72, 80, 56].map((width, line) => (
                <span
                  key={line}
                  className="block h-[3px] rounded-full bg-muted-foreground/20"
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
