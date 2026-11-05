"use client"

import { useEffect } from "react"
import { toast } from "sonner"

import { ApiError, friendlyMessage } from "@/lib/api-error"

interface LoadFailedProps {
  /** What went wrong: the error a query returned, or ready-made text. */
  error: unknown
  /** Used when the error carries no text of its own, e.g. "Could not load users." */
  fallback: string
  /** Said instead of the generic permission text when the server answered 403. */
  forbiddenMessage?: string
  onRetry?: () => void
  /** Only toast: for a small spot where a placeholder box would be out of proportion, or a failed action rather than a failed load. */
  quiet?: boolean
  className?: string
}

/**
 * What a page shows when something it needs could not be loaded.
 *
 * The reason is told once, as a toast, like every other error in the app - so this placeholder deliberately does not repeat
 * it. A message printed in the page next to the toast would just say the same thing twice.
 */
export function LoadFailed({ error, fallback, forbiddenMessage, onRetry, quiet, className }: LoadFailedProps) {
  useEffect(() => {
    if (!error) return
    const text =
      typeof error === "string"
        ? error
        : forbiddenMessage && error instanceof ApiError && error.status === 403
          ? forbiddenMessage
          : friendlyMessage(error, fallback)
    // Keyed by the text: several things failing for the same reason (a lost connection) show one toast, not one each.
    toast.error(text, { id: text })
  }, [error, fallback, forbiddenMessage])

  if (quiet) return null

  return (
    <div
      className={`flex flex-1 flex-col items-center justify-center rounded-lg border border-dashed border-[var(--border-strong)] bg-card px-6 py-12 text-center text-[13px] text-muted-foreground ${className ?? ""}`}
    >
      <p>Nothing to show right now.</p>
      {onRetry && (
        <button
          type="button"
          onClick={onRetry}
          className="mt-3 rounded-btn border border-[var(--border-strong)] bg-card font-semibold text-foreground hover:border-primary hover:bg-muted hover:text-primary h-[38px] px-4 text-[13px] inline-flex items-center justify-center"
        >
          Try again
        </button>
      )}
    </div>
  )
}
