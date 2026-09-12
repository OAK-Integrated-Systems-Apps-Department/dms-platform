import { Loader2 } from "lucide-react"

import { cn } from "@/lib/utils"

interface LoadingStateProps {
  /** Defaults to a generic "Loading…" — pass something specific, e.g. "Loading roles…", to match what's actually being fetched. */
  message?: string
  className?: string
}

/**
 * The one shared "this is loading" empty state — for a query/store fetch
 * that's still in flight, meant to replace an entire content area (a
 * table, a detail view, a form) the same way each page's own
 * loading/error branch already does.
 *
 * Deliberately mirrors `BackendUnavailable`'s shell (same
 * rounded-lg/py-16/shadow-[var(--ledger-shadow)] card, same icon-circle +
 * text layout) so the two read as one family instead of two unrelated
 * designs — swap only the icon (a spinning `Loader2` instead of
 * `ServerOff`) and drop the rust tint entirely, since this isn't an error:
 * neutral `border-border`/`bg-card`/`bg-muted`, matching every other
 * non-error card in the app.
 *
 * Not for every kind of "loading" in the app — an inline control (a
 * dropdown trigger, a button, a single line of text) that this would
 * visually dwarf should get its own small inline spinner instead of
 * pulling this in. See `WelcomeHeader`'s greeting and `RoleMultiSelect`'s
 * `loading` prop for that pattern.
 */
export function LoadingState({ message = "Loading…", className }: LoadingStateProps) {
  return (
    <div
      className={cn(
        "flex flex-col items-center gap-3 rounded-lg border border-border bg-card px-6 py-16 text-center shadow-[var(--ledger-shadow)]",
        className
      )}
    >
      <div className="flex size-14 items-center justify-center rounded-full border border-border bg-muted">
        <Loader2 size={24} className="animate-spin text-muted-foreground" />
      </div>
      <p className="text-[13.5px] text-muted-foreground">{message}</p>
    </div>
  )
}
