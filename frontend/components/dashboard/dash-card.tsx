"use client"

import {
  createContext,
  useContext,
  type CSSProperties,
  type ReactNode,
} from "react"
import Link from "next/link"
import type { LucideIcon } from "lucide-react"
import { ArrowUpRight, CloudOff, RefreshCw } from "lucide-react"

import { Button } from "@/components/ui/button"
import { cn } from "@/lib/utils"

/**
 * The shell of every dashboard block: a card that rises into place in turn
 * (`order`), with a heading, a line under it and an optional link.
 */
export function DashCard({
  title,
  subtitle,
  link,
  action,
  order = 0,
  className,
  children,
}: {
  title?: string
  subtitle?: ReactNode
  link?: { href: string; label: string }
  /** Anything else for the heading's right-hand side, instead of a link. */
  action?: ReactNode
  /** Its place in the entrance - each one rises a moment after the one before. */
  order?: number
  className?: string
  children: ReactNode
}) {
  return (
    <section
      style={{ "--dash-order": order } as CSSProperties}
      aria-label={title}
      className={cn(
        "dash-rise relative flex min-w-0 flex-col rounded-2xl border border-border bg-card p-5 shadow-[var(--ledger-shadow)]",
        className
      )}
    >
      {title && (
        <header className="mb-4 flex items-start justify-between gap-3">
          <div className="min-w-0">
            <h2 className="font-heading text-[16px] font-semibold">{title}</h2>
            {subtitle && (
              <p className="mt-0.5 text-[12.5px] text-muted-foreground">
                {subtitle}
              </p>
            )}
          </div>
          {action}
          {link && (
            <Link
              href={link.href}
              className="flex shrink-0 items-center gap-0.5 rounded-md px-1.5 py-1 text-[12.5px] font-semibold text-primary transition-colors hover:bg-[var(--primary-tint)] focus-visible:ring-2 focus-visible:ring-ring/50 focus-visible:outline-none"
            >
              {link.label}
              <ArrowUpRight size={13} aria-hidden="true" />
            </Link>
          )}
        </header>
      )}
      {children}
    </section>
  )
}

/** A shimmering stand-in while the figures load. */
export function DashSkeleton({ className }: { className?: string }) {
  return <div aria-hidden="true" className={cn("dash-skeleton", className)} />
}

/** Whether the dashboard figures failed to load, and how to ask again - for every section at once. */
const DashStatus = createContext<{ failed: boolean; retry: () => void }>({
  failed: false,
  retry: () => {},
})

export const DashStatusProvider = DashStatus.Provider

/**
 * What a section shows before its figures arrive: the skeleton while they
 * load, or - if loading failed - a short message with a retry, so one
 * failure never blanks the page.
 */
export function Pending({ skeleton }: { skeleton: ReactNode }) {
  const { failed, retry } = useContext(DashStatus)
  if (!failed) return <div aria-busy="true">{skeleton}</div>
  return <SectionError onRetry={retry} />
}

export function SectionError({
  message = "This couldn't be loaded just now.",
  onRetry,
}: {
  message?: string
  onRetry?: () => void
}) {
  return (
    <div
      role="alert"
      className="flex flex-1 flex-col items-center justify-center gap-2 py-8 text-center"
    >
      <CloudOff
        size={22}
        className="text-muted-foreground"
        aria-hidden="true"
      />
      <p className="text-[13px] text-muted-foreground">{message}</p>
      {onRetry && (
        <Button size="sm" variant="outline" onClick={onRetry}>
          <RefreshCw />
          Try again
        </Button>
      )}
    </div>
  )
}

/** A calm "nothing here yet", with what to do about it - never styled like an error. */
export function EmptyState({
  icon: Icon,
  title,
  text,
  action,
  className,
}: {
  icon: LucideIcon
  title: string
  text?: string
  action?: { href: string; label: string }
  className?: string
}) {
  return (
    <div
      className={cn(
        "flex flex-1 flex-col items-center justify-center px-4 py-8 text-center",
        className
      )}
    >
      <span className="mb-3 flex size-11 items-center justify-center rounded-2xl bg-[var(--primary-tint)] text-primary">
        <Icon size={20} aria-hidden="true" />
      </span>
      <p className="text-[13.5px] font-semibold">{title}</p>
      {text && (
        <p className="mt-1 max-w-[300px] text-[12.5px] text-muted-foreground">
          {text}
        </p>
      )}
      {action && (
        <Link
          href={action.href}
          className="mt-3 inline-flex items-center gap-1 rounded-md px-2 py-1 text-[12.5px] font-semibold text-primary hover:bg-[var(--primary-tint)]"
        >
          {action.label}
          <ArrowUpRight size={13} aria-hidden="true" />
        </Link>
      )}
    </div>
  )
}
