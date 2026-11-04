"use client"

import Link from "next/link"

import { Badge } from "@/components/ui/badge"

import type { OwnedDocumentItemDto } from "@/lib/ownership/types"

interface OwnedDocumentsTableProps {
  items: OwnedDocumentItemDto[]
  selected: Set<string>
  onToggle: (documentId: string) => void
  onTogglePage: (select: boolean) => void
  /** Rows can be ticked (the caller may run bulk transfers). */
  canSelect: boolean
  /** Rows get a Transfer button (the caller may move single documents). */
  canTransferOne: boolean
  onTransferOne: (item: OwnedDocumentItemDto) => void
  /** Show why each row is waiting (the pending list). */
  showPendingReason?: boolean
}

const TH = "px-3 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase"

const PENDING_REASONS: Record<string, string> = {
  holding: "Held for reassignment",
  owner_deactivated: "Owner deactivated",
}

/** The documents of one owner (or the ones waiting for a new owner): Recycle Bin and template rows are flagged, never hidden. */
export function OwnedDocumentsTable({
  items,
  selected,
  onToggle,
  onTogglePage,
  canSelect,
  canTransferOne,
  onTransferOne,
  showPendingReason,
}: OwnedDocumentsTableProps) {
  const allTicked = items.length > 0 && items.every((item) => selected.has(item.documentId))
  return (
    <div className="overflow-hidden rounded-lg border border-border bg-card shadow-[var(--ledger-shadow)]">
      <div className="overflow-x-auto">
        <table className="w-full min-w-[820px] border-collapse">
          <thead>
            <tr className="border-b border-border">
              {canSelect && (
                <th className="w-10 px-3 py-2.5">
                  <input
                    type="checkbox"
                    aria-label="Select all on this page"
                    checked={allTicked}
                    onChange={(e) => onTogglePage(e.target.checked)}
                  />
                </th>
              )}
              <th className={TH}>Document</th>
              <th className={TH}>Type</th>
              <th className={TH}>Owner</th>
              <th className={TH}>Department</th>
              {showPendingReason && <th className={TH}>Waiting because</th>}
              <th className={TH}>
                <span className="sr-only">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {items.length === 0 && (
              <tr>
                <td colSpan={7} className="px-3 py-10 text-center text-[13px] text-muted-foreground">
                  No documents.
                </td>
              </tr>
            )}
            {items.map((item) => (
              <tr key={item.documentId} className="border-b border-border last:border-b-0">
                {canSelect && (
                  <td className="px-3 py-2.5">
                    <input
                      type="checkbox"
                      aria-label={`Select ${item.title}`}
                      checked={selected.has(item.documentId)}
                      onChange={() => onToggle(item.documentId)}
                    />
                  </td>
                )}
                <td className="px-3 py-2.5 text-[13px]">
                  <Link href={`/documents/${item.documentId}`} className="font-semibold text-foreground hover:text-primary hover:underline">
                    {item.title}
                  </Link>
                  <span className="ml-2 inline-flex gap-1.5 align-middle">
                    {item.inRecycleBin && <Badge variant="muted">Recycle Bin</Badge>}
                    {item.template && <Badge variant="outline">Template</Badge>}
                    {item.ownershipState === "holding" && <Badge variant="gold">Holding</Badge>}
                  </span>
                  {item.folderName && <p className="text-[12px] text-muted-foreground">{item.folderName}</p>}
                </td>
                <td className="px-3 py-2.5 text-[13px] text-muted-foreground">{item.documentTypeName ?? "—"}</td>
                <td className="px-3 py-2.5 text-[13px]">
                  {item.owner.name}
                  {item.owner.status === "deactivated" && (
                    <Badge variant="muted" className="ml-2">
                      Deactivated
                    </Badge>
                  )}
                </td>
                <td className="px-3 py-2.5 text-[13px] text-muted-foreground">{item.ownerDepartment?.name ?? "—"}</td>
                {showPendingReason && (
                  <td className="px-3 py-2.5 text-[13px] text-muted-foreground">
                    {item.pendingReason ? (PENDING_REASONS[item.pendingReason] ?? item.pendingReason) : "—"}
                  </td>
                )}
                <td className="px-3 py-2.5 text-right">
                  {canTransferOne && (
                    <button
                      type="button"
                      onClick={() => onTransferOne(item)}
                      className="border border-[var(--border-strong)] bg-card font-semibold text-foreground hover:border-primary hover:text-primary h-[38px] rounded-btn px-4 text-[13px] inline-flex items-center justify-center"
                    >
                      Transfer
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
