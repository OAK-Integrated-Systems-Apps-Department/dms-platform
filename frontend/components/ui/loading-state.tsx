import { cn } from "@/lib/utils"

interface LoadingStateProps {
  /** Defaults to a generic "Loading…" - pass something specific, e.g. "Loading roles…", to match what is being fetched. */
  message?: string
  className?: string
}

/**
 * The one shared "this is loading" state, for a query or store fetch still in flight. It replaces a whole content area
 * (a table, a detail view, a form) with a small document-themed animation: a stack of sheets with a scan line sweeping
 * over the top one, drawn in the app's own green, gold and card colours (so it follows light and dark mode), with the
 * message underneath. The motion is pure CSS and stops for people who ask for reduced motion (see `.dms-loader` in
 * app/globals.css).
 *
 * Not for every kind of "loading": an inline control (a dropdown trigger, a button, one line of text) would be dwarfed
 * by this and should get its own small inline spinner instead.
 */
export function LoadingState({ message = "Loading…", className }: LoadingStateProps) {
  return (
    <div
      role="status"
      aria-live="polite"
      className={cn(
        "dms-loader flex min-h-[260px] flex-1 flex-col items-center justify-center gap-5 rounded-lg border border-border bg-card px-6 py-14 text-center shadow-[var(--ledger-shadow)]",
        className
      )}
    >
      <LoaderArt />
      <div className="flex flex-col items-center gap-2">
        <p className="text-[13.5px] font-medium text-foreground">{message}</p>
        <span className="dms-loader-dots" aria-hidden="true">
          <i />
          <i />
          <i />
        </span>
      </div>
    </div>
  )
}

/** The animated stack of sheets, shared by the in-page loading state and the full-screen one. */
export function LoaderArt() {
  return (
    <div className="dms-loader-art" aria-hidden="true">
      <span className="dms-sheet dms-sheet-back" />
      <span className="dms-sheet dms-sheet-mid" />
      <span className="dms-sheet dms-sheet-front">
        <i className="dms-line dms-line-title" />
        <i className="dms-line" />
        <i className="dms-line" />
        <i className="dms-line dms-line-short" />
        <b className="dms-seal" />
        <u className="dms-scan" />
      </span>
    </div>
  )
}
