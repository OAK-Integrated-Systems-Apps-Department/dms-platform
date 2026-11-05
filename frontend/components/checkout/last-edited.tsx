import { formatDateTime } from "@/lib/utils"
import type { CheckoutStatusDto } from "@/lib/checkout/api"

/**
 * Who last changed the document, and when: "Last edited by Alice Perera · 2026-11-03, 09:15 · version 3". A document
 * that was only ever uploaded says "Uploaded by ..." instead, so a first version is never described as an edit.
 * The time and name are those of the CURRENT version - uploaded, checked in, or saved from the online editor - so it
 * moves on by itself as people save. Renders nothing until the document has a version.
 */
export function LastEdited({ status, className }: { status: CheckoutStatusDto; className?: string }) {
  if (!status.lastEditedAt || !status.lastEditedByName) return null
  const verb = status.lastEditedVersion > 1 ? "Last edited by" : "Uploaded by"
  return (
    <p className={className ?? "text-[12.5px] text-muted-foreground"}>
      {verb} <span className="font-medium text-foreground">{status.lastEditedByName}</span> ·{" "}
      {formatDateTime(status.lastEditedAt)}
      {status.lastEditedVersion > 1 && <> · version {status.lastEditedVersion}</>}
    </p>
  )
}
