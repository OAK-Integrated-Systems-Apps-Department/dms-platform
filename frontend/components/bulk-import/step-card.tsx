import type { ReactNode } from "react"
import { AlertTriangle, CheckCircle2 } from "lucide-react"

import { cn } from "@/lib/utils"

interface StepCardProps {
  step?: number
  title: string
  description?: ReactNode
  /** Right-aligned header content, e.g. a Cancel import button. */
  action?: ReactNode
  children?: ReactNode
  className?: string
}

/** One numbered section of the Bulk Import screen. */
export function StepCard({ step, title, description, action, children, className }: StepCardProps) {
  return (
    <section
      aria-labelledby={step ? `bulk-import-step-${step}` : undefined}
      className={cn(
        "rounded-lg border border-border bg-card px-[26px] py-[22px] shadow-[var(--ledger-shadow)]",
        className
      )}
    >
      <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
        <div className="flex items-start gap-3">
          {step !== undefined && (
            <span
              aria-hidden="true"
              className="flex size-7 shrink-0 items-center justify-center rounded-md bg-primary/10 text-[13px] font-bold text-primary"
            >
              {step}
            </span>
          )}
          <div>
            <h2 id={step ? `bulk-import-step-${step}` : undefined} className="font-heading text-[16px] font-semibold">
              {step !== undefined && <span className="sr-only">Step {step}: </span>}
              {title}
            </h2>
            {description && (
              <p className="mt-0.5 text-[13px] leading-[1.6] text-muted-foreground">{description}</p>
            )}
          </div>
        </div>
        {action}
      </div>
      {children}
    </section>
  )
}

/** Backend or validation error shown in place, next to what caused it. */
export function InlineError({ children }: { children: ReactNode }) {
  return (
    <p
      role="alert"
      className="mt-3 flex items-start gap-2 rounded-btn border border-[var(--rust)] bg-[var(--rust-tint)] px-3.5 py-2.5 text-[12.5px] leading-[1.55] text-[var(--rust)]"
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
      className="mb-4 flex items-start gap-2 rounded-btn border border-primary/30 bg-primary/5 px-3.5 py-2.5 text-[12.5px] leading-[1.55] text-foreground"
    >
      <CheckCircle2 size={15} className="mt-px shrink-0 text-primary" aria-hidden="true" />
      <span>{children}</span>
    </p>
  )
}
