import { ServerOff } from "lucide-react"

import { cn } from "@/lib/utils"

interface BackendUnavailableProps {
  /** Shown as a "Try again" button when provided — typically a query's `refetch`, or a store hook's own retry function. */
  onRetry?: () => void
  className?: string
}

/**
 * The one shared "can't reach the server" empty state — for a query/store
 * fetch that failed with a connection-level error (`isNetworkError`, see
 * lib/api-error.ts), as opposed to the server responding with a real error
 * status (4xx/5xx), which callers keep handling however they already do.
 *
 * Meant to replace an entire content area (a table, a detail view, a
 * dashboard's panels) the same way each page's own loading/error branch
 * already does — not a message of its own (the global handler in providers.tsx already toasts a lost connection once, and repeating it
 * here would say the same thing twice), and not a full-page takeover like
 * app/error/error-content.tsx (that page is for a signed-out, pre-auth
 * dead end with a "back to login" CTA; this is for an authenticated page
 * that's still perfectly usable once the backend comes back, so it keeps
 * the surrounding DashboardShell/sidebar and offers a retry instead).
 */
export function BackendUnavailable({
  onRetry,
  className,
}: BackendUnavailableProps) {
  return (
    <div
      className={cn(
        "flex flex-1 flex-col items-center justify-center gap-3 rounded-lg border border-[var(--rust)] bg-[var(--rust-tint)] px-6 py-16 text-center shadow-[var(--ledger-shadow)]",
        className
      )}
    >
      <div className="flex size-14 items-center justify-center rounded-full border border-[color-mix(in_srgb,var(--rust)_40%,transparent)] bg-card">
        <ServerOff size={24} className="text-[var(--rust)]" />
      </div>
      <p className="text-[13.5px] text-muted-foreground">Nothing to show right now.</p>
      {onRetry && (
        <button
          type="button"
          onClick={onRetry}
          className="mt-1 rounded-btn border border-[var(--border-strong)] bg-card font-semibold text-foreground hover:border-primary hover:bg-muted hover:text-primary h-[38px] px-4 text-[13px] inline-flex items-center justify-center"
        >
          Try again
        </button>
      )}
    </div>
  )
}
