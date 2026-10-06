import type { CSSProperties, ReactNode } from "react"
import Link from "next/link"
import { ArrowUpRight } from "lucide-react"

import { cn } from "@/lib/utils"

/**
 * The shell of every dashboard block: a card that rises into place in turn
 * (`order`), with a heading, a line under it and an optional link.
 */
export function DashCard({
  title,
  subtitle,
  link,
  order = 0,
  className,
  children,
}: {
  title?: string
  subtitle?: ReactNode
  link?: { href: string; label: string }
  /** Its place in the entrance - each one rises a moment after the one before. */
  order?: number
  className?: string
  children: ReactNode
}) {
  return (
    <section
      style={{ "--dash-order": order } as CSSProperties}
      className={cn(
        "dash-rise relative flex min-w-0 flex-col rounded-2xl border border-border bg-card p-5 shadow-[var(--ledger-shadow)]",
        className
      )}
    >
      {title && (
        <header className="mb-4 flex items-start justify-between gap-3">
          <div className="min-w-0">
            <h2 className="font-heading text-[16.5px] font-semibold">
              {title}
            </h2>
            {subtitle && (
              <p className="mt-0.5 text-[12.5px] text-muted-foreground">
                {subtitle}
              </p>
            )}
          </div>
          {link && (
            <Link
              href={link.href}
              className="flex shrink-0 items-center gap-0.5 rounded-md px-1.5 py-1 text-[12.5px] font-semibold text-primary transition-colors hover:bg-[var(--primary-tint)]"
            >
              {link.label}
              <ArrowUpRight size={13} />
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
