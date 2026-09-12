import { ServerOff } from "lucide-react"

import { NETWORK_ERROR_MESSAGE } from "@/lib/api-fetch"
import { cn } from "@/lib/utils"

interface BackendUnavailableProps {
  /** Defaults to the same copy `apiFetch` uses for a network failure. Override for a more specific message if the caller has one. */
  message?: string
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
 * already does — not a toast, and not a full-page takeover like
 * app/error/error-content.tsx (that page is for a signed-out, pre-auth
 * dead end with a "back to login" CTA; this is for an authenticated page
 * that's still perfectly usable once the backend comes back, so it keeps
 * the surrounding DashboardShell/sidebar and offers a retry instead).
 */
export function BackendUnavailable({
  message = NETWORK_ERROR_MESSAGE,
  onRetry,
  className,
}: BackendUnavailableProps) {
  return (
    <div
      className={cn(
        "flex flex-col items-center gap-3 rounded-lg border border-[var(--rust)] bg-[var(--rust-tint)] px-6 py-16 text-center shadow-[var(--ledger-shadow)]",
        className
      )}
    >
      <div className="flex size-14 items-center justify-center rounded-full border border-[color-mix(in_srgb,var(--rust)_40%,transparent)] bg-card">
        <ServerOff size={24} className="text-[var(--rust)]" />
      </div>
      <div>
        <h2 className="font-heading text-[15px] font-semibold text-foreground">
          Can&apos;t reach the server
        </h2>
        <p className="mt-1 max-w-[360px] text-[13.5px] leading-[1.5] text-muted-foreground">
          {message}
        </p>
      </div>
      {onRetry && (
        <button
          type="button"
          onClick={onRetry}
          className="mt-1 rounded-btn border border-[var(--border-strong)] bg-card px-4 py-2 text-[13px] font-semibold text-foreground hover:border-primary hover:bg-muted hover:text-primary"
        >
          Try again
        </button>
      )}
    </div>
  )
}
