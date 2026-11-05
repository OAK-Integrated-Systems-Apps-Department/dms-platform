"use client"

import { Bookmark } from "lucide-react"

import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { useBookmarkStatus, useBookmarkToggle } from "@/lib/bookmarks/use-bookmarks"
import { cn } from "@/lib/utils"

export interface BookmarkTarget {
  documentId: string
  title: string
}

/**
 * The bookmark control (BD-FR-007): an outline bookmark when it isn't bookmarked, a filled one in the
 * primary colour when it is - visually distinct from {@code PinButton}'s pin glyph (a different lucide
 * icon entirely, not just a colour swap), so the two controls read as two different things when placed
 * side by side.
 *
 * One click adds or removes it - unlike pinning, a bookmark has no audience to choose, so there's no
 * sheet to open first (BD-NFR-001).
 *
 * `bookmarked` is optional: pass it down from a parent that already batched a `useBookmarkStatus` call
 * for every row on the page (the document browser, search results, Shared with me), so one status
 * request covers the whole page instead of one per button. Omit it for a single, standalone placement
 * (the preview, the document's own page), where this component fetches its own status.
 */
export function BookmarkButton({
  row,
  bookmarked: bookmarkedProp,
  className,
}: {
  row: BookmarkTarget
  bookmarked?: boolean
  className?: string
}) {
  const { hasPermission } = usePermissions()
  const canAdd = hasPermission(PermissionCodes.BD_BOOKMARK)
  const { toggle, isSaving } = useBookmarkToggle()
  const { isBookmarked } = useBookmarkStatus(bookmarkedProp === undefined ? [row.documentId] : [])
  const bookmarked = bookmarkedProp ?? isBookmarked(row.documentId)

  // Never hide the remove control for an existing bookmark, even without BD_BOOKMARK (a role change
  // must not strand someone unable to clean up their own bookmark - the same reasoning as the backend's
  // unbookmark endpoint needing only authentication).
  if (!bookmarked && !canAdd) return null

  const label = bookmarked ? `Remove ${row.title} from bookmarks` : `Bookmark ${row.title}`

  return (
    <button
      type="button"
      aria-pressed={bookmarked}
      aria-label={label}
      title={bookmarked ? "Bookmarked - click to remove" : "Add a private bookmark"}
      disabled={isSaving}
      onClick={(event) => {
        event.stopPropagation()
        void toggle(row.documentId, bookmarked).catch(() => {})
      }}
      className={cn(
        "flex size-8 items-center justify-center rounded-btn transition-colors focus-visible:ring-2 focus-visible:ring-ring/50 focus-visible:outline-none disabled:opacity-60",
        bookmarked
          ? "text-[var(--gold-foreground)] hover:bg-[var(--gold-tint)]"
          : "text-muted-foreground hover:bg-muted hover:text-[var(--gold-foreground)]",
        className
      )}
    >
      <Bookmark size={16} fill={bookmarked ? "currentColor" : "none"} />
    </button>
  )
}

/** A small filled bookmark mark, for a row with no room for the full button - mirrors {@code PinnedMark}. */
export function BookmarkedMark({ className }: { className?: string }) {
  return (
    <span
      role="img"
      aria-label="Bookmarked"
      title="Bookmarked"
      className={cn("inline-flex shrink-0 items-center text-[var(--gold-foreground)]", className)}
    >
      <Bookmark size={13} fill="currentColor" />
    </span>
  )
}

/**
 * {@link BookmarkedMark} for one document, shown only when the caller bookmarked it.
 *
 * `bookmarked` is optional, the same contract as {@link BookmarkButton}: a list (the document browser,
 * search results, Shared with me) should batch one `useBookmarkStatus(allVisibleIds)` call for the
 * whole page and pass each row's answer down here, rather than every row's indicator making its own
 * `status` call - that per-row fallback exists only for a single, standalone placement.
 */
export function BookmarkedIndicator({
  documentId,
  bookmarked: bookmarkedProp,
  className,
}: {
  documentId: string
  bookmarked?: boolean
  className?: string
}) {
  const { isBookmarked } = useBookmarkStatus(bookmarkedProp === undefined ? [documentId] : [])
  const bookmarked = bookmarkedProp ?? isBookmarked(documentId)
  return bookmarked ? <BookmarkedMark className={className} /> : null
}
