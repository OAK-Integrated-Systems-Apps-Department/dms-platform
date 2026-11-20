import { cn } from "@/lib/utils"

interface LoadingStateProps {
  /** Defaults to a generic "Loading…" - pass something specific, e.g. "Loading roles…", to match what is being fetched. */
  message?: string
  className?: string
}

/**
 * The one shared "this is loading" state, for a query or store fetch still in flight. It replaces a whole content area
 * (a table, a detail view, a form) with the growing-oak GIF (see LoaderArt) with the message underneath.
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

/**
 * The growing-oak animation (public/images/loading.gif), shared by the in-page loading state and the full-screen one.
 * Lives under public/images/ because the proxy lets that folder through without a session, so it also shows before sign-in.
 */
export function LoaderArt({ size = 200 }: { size?: number }) {
  return (
    // An animated GIF: next/image would not animate it, and the file is small and local.
    // eslint-disable-next-line @next/next/no-img-element
    <img
      src="/images/loading.gif"
      alt=""
      aria-hidden="true"
      width={size}
      height={Math.round((size * 303) / 313)}
      className="select-none"
    />
  )
}
