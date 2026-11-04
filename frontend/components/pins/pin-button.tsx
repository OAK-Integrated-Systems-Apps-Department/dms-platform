"use client"

import { useQuery } from "@tanstack/react-query"
import { Pin } from "lucide-react"

import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { fetchPins } from "@/lib/pins/api"
import { PINS_KEY, usePins, type PinTarget } from "@/lib/pins/use-pins"
import { cn } from "@/lib/utils"

/**
 * The pin control, the same everywhere a document appears (PID-NFR-002): an outline pin when it isn't
 * pinned for you, a filled pin in the primary colour when it is (PID-FR-004).
 *
 * Clicking opens the audience sheet: choosing who sees a pin is part of pinning. For a pin you placed
 * the sheet changes the audience or unpins it. A pin placed for you by someone else is shown but can't be
 * changed from here - only whoever pinned it can unpin it - so without the pin permission it is a plain
 * mark with the pinner's name; with it, the button lets you add a pin of your own.
 */
export function PinButton({
  row,
  className,
}: {
  row: PinTarget
  className?: string
}) {
  const { pinOf, canToggle, openPin } = usePins()
  const pin = pinOf(row.documentId)
  const pinned = pin !== undefined

  if (!canToggle(row.documentId)) {
    return pin && !pin.mine ? (
      <PinnedMark
        by={pin.pinnedByName}
        className={cn("size-8 justify-center", className)}
      />
    ) : null
  }

  const label = pin?.mine
    ? `Change who sees ${row.title}`
    : pinned
      ? `Pin ${row.title} yourself (already pinned by ${pin.pinnedByName})`
      : `Pin ${row.title}`

  return (
    <button
      type="button"
      aria-pressed={pinned}
      aria-label={label}
      title={
        pin?.mine
          ? "Pinned by you - change who sees it, or unpin"
          : pinned
            ? `Pinned by ${pin.pinnedByName} - click to pin it yourself`
            : "Pin, and choose who sees it"
      }
      onClick={(event) => {
        event.stopPropagation()
        openPin(row)
      }}
      className={cn(
        "flex size-8 items-center justify-center rounded-btn transition-colors focus-visible:ring-2 focus-visible:ring-ring/50 focus-visible:outline-none",
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

/**
 * A small filled pin shown beside a pinned document's name, where there is no room for the button.
 * `by` names who pinned it when that is someone else.
 */
export function PinnedMark({
  className,
  by,
}: {
  className?: string
  by?: string
}) {
  const title = by ? `Pinned by ${by}` : "Pinned"
  return (
    <span
      role="img"
      aria-label={title}
      title={title}
      className={cn(
        "inline-flex shrink-0 items-center text-primary",
        className
      )}
    >
      <Pin size={13} fill="currentColor" />
    </span>
  )
}

/**
 * {@link PinnedMark} for one document, shown only when it is pinned for the caller (PID-FR-004: the
 * status is visible wherever the document appears). Reads the shared list through a selector, so a long
 * document list does not re-render every row when one pin changes.
 */
export function PinnedIndicator({
  documentId,
  className,
}: {
  documentId: string
  className?: string
}) {
  const { hasPermission } = usePermissions()
  const { data: pin } = useQuery({
    queryKey: PINS_KEY,
    queryFn: fetchPins,
    enabled: hasPermission(PermissionCodes.DOCM_DOCUMENTS_VIEW),
    staleTime: 30_000,
    select: (data) => {
      const found = data.pins.find((p) => p.document.documentId === documentId)
      return found ? { mine: found.mine, by: found.pinnedByName } : null
    },
  })
  return pin ? (
    <PinnedMark by={pin.mine ? undefined : pin.by} className={className} />
  ) : null
}
