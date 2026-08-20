import { cn } from "@/lib/utils"

import type { DocumentStatus } from "../types"

const STAMP_STYLES: Record<DocumentStatus, string> = {
  Approved: "border-primary text-primary bg-primary/10",
  Pending: "border-[var(--gold)] text-[var(--gold)] bg-[var(--gold-tint)]",
  Draft: "border-[var(--border-strong)] text-[var(--ink-faint)] bg-muted",
  Rejected: "border-[var(--rust)] text-[var(--rust)] bg-[var(--rust-tint)]",
}

/**
 * The "ink stamp" status badge — the signature motif of this theme.
 * Slightly rotated, serif, uppercase, with a small dot.
 */
export function Stamp({ status }: { status: DocumentStatus }) {
  return (
    <span
      className={cn(
        "-rotate-2 inline-flex items-center gap-1.5 rounded-full border-[1.5px] py-[3px] pr-2.5 pl-2 font-heading text-[11.5px] font-semibold tracking-wide uppercase",
        STAMP_STYLES[status]
      )}
    >
      <span className="size-[5px] rounded-full bg-current" />
      {status}
    </span>
  )
}
