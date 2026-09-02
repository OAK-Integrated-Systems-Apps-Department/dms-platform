import { ArrowDown, ArrowUp, ChevronsUpDown } from "lucide-react"

import { cn } from "@/lib/utils"

interface SortableThProps {
  label: string
  active: boolean
  direction: "asc" | "desc"
  onClick: () => void
  className?: string
}

/**
 * `<th>` with a clickable label + direction indicator, used by every
 * sortable admin table (Users, Roles, Role Details' Assigned Users).
 * Purely presentational — the table/page owns the actual sort state and
 * passes down whether this column is the active one and which way.
 */
export function SortableTh({
  label,
  active,
  direction,
  onClick,
  className,
}: SortableThProps) {
  return (
    <th
      className={cn(
        "px-3 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase",
        className
      )}
    >
      <button
        type="button"
        onClick={onClick}
        aria-label={`Sort by ${label}`}
        className={cn(
          "flex items-center gap-1 tracking-[0.06em] uppercase transition-colors hover:text-foreground",
          active && "text-foreground"
        )}
      >
        {label}
        {active ? (
          direction === "asc" ? (
            <ArrowUp size={12} />
          ) : (
            <ArrowDown size={12} />
          )
        ) : (
          <ChevronsUpDown size={12} className="opacity-40" />
        )}
      </button>
    </th>
  )
}