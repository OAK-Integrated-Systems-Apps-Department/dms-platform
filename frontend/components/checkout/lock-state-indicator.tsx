import { Gavel, PenLine } from "lucide-react"

import type { DocumentListItemDto } from "@/lib/documents/api"

/**
 * The document list's small lock marker (CICO-FR-002): a pen when someone has it checked out for editing
 * (gold when it is you, muted when it is somebody else), a gavel under legal hold. Nothing for a free
 * document, so it stays out of the way. Who holds it is one click away on the document page.
 */
export function LockStateIndicator({ lockState }: { lockState: DocumentListItemDto["lockState"] }) {
  if (!lockState) return null
  if (lockState === "legal_hold") {
    return (
      <span title="Under legal hold - cannot be changed" className="inline-flex shrink-0 text-[var(--rust)]">
        <Gavel size={12} aria-label="Under legal hold" />
      </span>
    )
  }
  const mine = lockState === "checked_out_by_me"
  return (
    <span
      title={mine ? "Checked out by you" : "Checked out by someone else"}
      className={`inline-flex shrink-0 ${mine ? "text-[var(--gold-foreground)]" : "text-muted-foreground"}`}
    >
      <PenLine size={12} aria-label={mine ? "Checked out by you" : "Checked out by someone else"} />
    </span>
  )
}
