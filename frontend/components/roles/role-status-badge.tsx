import { cn } from "@/lib/utils"

import type { RoleStatus } from "./types"

const STAMP_STYLES: Record<RoleStatus, string> = {
  Active: "border-primary text-primary bg-primary/10",
  Inactive: "border-[var(--rust)] text-[var(--rust)] bg-[var(--rust-tint)]",
}

/**
 * Same "ink stamp" motif as `components/dashboard/status-stamp.tsx`'s
 * `Stamp`, scoped to `RoleStatus` instead of `DocumentStatus` so the two
 * status vocabularies stay independent.
 */
export function RoleStatusBadge({ status }: { status: RoleStatus }) {
  return (
    <span
      className={cn(
        "inline-flex -rotate-2 items-center gap-1.5 rounded-full border-[1.5px] py-[3px] pr-2.5 pl-2 font-heading text-[11.5px] font-semibold tracking-wide uppercase",
        STAMP_STYLES[status]
      )}
    >
      <span className="size-[5px] rounded-full bg-current" />
      {status}
    </span>
  )
}
