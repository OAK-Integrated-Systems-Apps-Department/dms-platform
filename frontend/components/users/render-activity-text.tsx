import { Fragment } from "react"

/**
 * Renders `**bold**` segments in an activity/audit entry's `text` as
 * `<strong>`, everything else as plain text.
 *
 * Extracted out of `UserDetailView` (which originally had a private copy
 * for its "Recent activity" section) so `UserAuditHistory` (U10) — which
 * renders the same `UserActivityEntry.text` values in its feed — doesn't
 * duplicate the same regex/markup.
 */
export function renderActivityText(text: string) {
  const parts = text.split(/\*\*(.+?)\*\*/g)
  return parts.map((part, i) =>
    i % 2 === 1 ? (
      <strong key={i} className="font-semibold text-foreground">
        {part}
      </strong>
    ) : (
      <Fragment key={i}>{part}</Fragment>
    )
  )
}
