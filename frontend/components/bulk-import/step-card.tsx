import type { ReactNode } from "react"
import { AlertTriangle, CheckCircle2, type LucideIcon } from "lucide-react"

import { cn } from "@/lib/utils"

interface StepCardProps {
  step?: number
  /** Shown in a tinted square when there's no step number. */
  icon?: LucideIcon
  title: string
  description?: ReactNode
  /** Right-aligned header content, e.g. a Cancel import button. */
  action?: ReactNode
  children?: ReactNode
  className?: string
}

/** One section of the Bulk Import screen. */
export function StepCard({
  step,
  icon: Icon,
  title,
  description,
  action,
  children,
  className,
}: StepCardProps) {
  const headingId = step ? `bulk-import-step-${step}` : undefined
  return (
    <section
      aria-labelledby={headingId}
      className={cn(
        "rounded-xl border border-border bg-card p-5 shadow-[var(--ledger-shadow)] sm:p-6",
        className
      )}
    >
      <div
        className={cn(
          "flex flex-wrap items-start justify-between gap-3",
          children && "mb-5"
        )}
      >
        <div className="flex min-w-0 items-start gap-3.5">
          {step !== undefined ? (
            <StepBadge step={step} />
          ) : (
            Icon && (
              <span
                aria-hidden="true"
                className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary"
              >
                <Icon size={19} />
              </span>
            )
          )}
          <div className="min-w-0">
            <h2 id={headingId} className="text-[15px] font-semibold">
              {step !== undefined && (
                <span className="sr-only">Step {step}: </span>
              )}
              {title}
            </h2>
            {description && (
              <p className="mt-1 max-w-[680px] text-[13px] leading-[1.6] text-muted-foreground">
                {description}
              </p>
            )}
          </div>
        </div>
        {action}
      </div>
      {children}
    </section>
  )
}

export function StepBadge({ step }: { step: number }) {
  return (
    <span
      aria-hidden="true"
      className="flex size-7 shrink-0 items-center justify-center rounded-full bg-primary text-[12.5px] font-semibold text-primary-foreground"
    >
      {step}
    </span>
  )
}

/** A labelled number in a soft tile, for an import's facts (type, files, created). */
export function StatTile({
  label,
  children,
}: {
  label: string
  children: ReactNode
}) {
  return (
    <div className="rounded-lg border border-border bg-muted/30 px-3.5 py-3">
      <dt className="text-[11.5px] font-medium tracking-[0.04em] text-muted-foreground uppercase">
        {label}
      </dt>
      <dd className="mt-1 flex flex-wrap items-center gap-2 text-[14px] font-semibold">
        {children}
      </dd>
    </div>
  )
}

/** Thin progress bar; `value` of `max`. */
export function ProgressBar({
  value,
  max,
  label,
  tone = "primary",
}: {
  value: number
  max: number
  label: string
  tone?: "primary" | "gold"
}) {
  const percent = max > 0 ? Math.min(100, Math.round((value / max) * 100)) : 0
  return (
    <div
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={max}
      aria-valuenow={value}
      className="h-1.5 w-full overflow-hidden rounded-full bg-muted"
    >
      <div
        className={cn(
          "h-full rounded-full transition-[width] duration-500",
          tone === "gold" ? "bg-[var(--gold)]" : "bg-primary"
        )}
        style={{ width: `${percent}%` }}
      />
    </div>
  )
}

/** Backend or validation error shown in place, next to what caused it. */
export function InlineError({ children }: { children: ReactNode }) {
  return (
    <p
      role="alert"
      className="mt-3 flex items-start gap-2 rounded-lg border border-[var(--rust)] bg-[var(--rust-tint)] px-3.5 py-2.5 text-[12.5px] leading-[1.55] text-[var(--rust)]"
    >
      <AlertTriangle size={15} className="mt-px shrink-0" aria-hidden="true" />
      <span>{children}</span>
    </p>
  )
}

export function InlineSuccess({ children }: { children: ReactNode }) {
  return (
    <p
      role="status"
      className="mb-4 flex items-start gap-2 rounded-lg border border-primary/30 bg-primary/5 px-3.5 py-2.5 text-[12.5px] leading-[1.55] text-foreground"
    >
      <CheckCircle2
        size={15}
        className="mt-px shrink-0 text-primary"
        aria-hidden="true"
      />
      <span>{children}</span>
    </p>
  )
}
