"use client"

import { useQuery } from "@tanstack/react-query"
import { Pin } from "lucide-react"

import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { fetchPins } from "@/lib/pins/api"
import { PINS_KEY, usePins, type PinTarget } from "@/lib/pins/use-pins"
import { cn } from "@/lib/utils"

/**
 * The pin control, the same everywhere a document appears (PID-NFR-002): an
 * outline pin when it isn't pinned, a filled pin in the primary colour when it
 * is (PID-FR-004). Hidden for someone who may not pin or unpin it.
 */
export function PinButton({
  row,
  className,
}: {
  row: PinTarget
  className?: string
}) {
  const { isPinned, canToggle, toggle } = usePins()
  const pinned = isPinned(row.documentId)
  if (!canToggle(row.documentId)) return null

  return (
    <button
      type="button"
      aria-pressed={pinned}
      aria-label={pinned ? `Unpin ${row.title}` : `Pin ${row.title}`}
      title={pinned ? "Unpin" : "Pin to your dashboard"}
      onClick={(event) => {
        event.stopPropagation()
        void toggle(row)
      }}
      className={cn(
        "flex size-8 items-center justify-center rounded-lg transition-colors focus-visible:ring-2 focus-visible:ring-ring/50 focus-visible:outline-none",
        pinned
          ? "text-primary hover:bg-primary/10"
          : "text-muted-foreground hover:bg-muted hover:text-primary",
        className
      )}
    >
      <Pin size={16} fill={pinned ? "currentColor" : "none"} />
    </button>
  )
}

/** A small filled pin shown beside a pinned document's name, where there is no room for the button. */
export function PinnedMark({ className }: { className?: string }) {
  return (
    <span
      role="img"
      aria-label="Pinned"
      title="Pinned"
      className={cn("inline-flex shrink-0 text-primary", className)}
    >
      <Pin size={13} fill="currentColor" />
    </span>
  )
}

/**
 * {@link PinnedMark} for one document, shown only when it is pinned (PID-FR-004: the status is visible
 * wherever the document appears). Reads the shared list through a selector, so a long document list
 * does not re-render every row when one pin changes.
 */
export function PinnedIndicator({
  documentId,
  className,
}: {
  documentId: string
  className?: string
}) {
  const { hasPermission } = usePermissions()
  const { data: pinned } = useQuery({
    queryKey: PINS_KEY,
    queryFn: fetchPins,
    enabled: hasPermission(PermissionCodes.DOCM_DOCUMENTS_VIEW),
    staleTime: 30_000,
    select: (data) => data.pins.some((p) => p.document.documentId === documentId),
  })
  return pinned ? <PinnedMark className={className} /> : null
}
