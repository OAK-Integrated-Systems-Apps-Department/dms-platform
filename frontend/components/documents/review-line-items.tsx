"use client"

import { Badge } from "@/components/ui/badge"

import type { ReviewLineItem } from "@/lib/documents/api"

interface ReviewLineItemsProps {
  items: ReviewLineItem[]
  onJumpToPage?: (page: number) => void
}

/**
 * The invoice lines as OCR read them. Read-only in this phase.
 *
 * Correcting a line is a different problem from correcting a field: a
 * line has four values and a position, and "wrong" often means two rows
 * were merged or one was split rather than a number being misread.
 * Shipping an editor for that before there is any evidence of what
 * reviewers actually need to fix would be guessing. Showing them, so a
 * reviewer can see a bad total came from a bad line, is not.
 */
export function ReviewLineItems({ items, onJumpToPage }: ReviewLineItemsProps) {
  if (items.length === 0) return null

  return (
    <div className="overflow-hidden rounded-lg border border-border bg-card shadow-[var(--ledger-shadow)]">
      <div className="flex items-center justify-between border-b border-border px-3 py-2.5">
        <span className="text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
          Line items
        </span>
        <span className="text-[11.5px] text-muted-foreground">read-only</span>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full min-w-[520px] border-collapse">
          <thead>
            <tr className="border-b border-border">
              <th className="px-3 py-2 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
                Description
              </th>
              <th className="px-3 py-2 text-right text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
                Qty
              </th>
              <th className="px-3 py-2 text-right text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
                Unit price
              </th>
              <th className="px-3 py-2 text-right text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
                Total
              </th>
              <th className="px-3 py-2 text-right text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
                Confidence
              </th>
            </tr>
          </thead>
          <tbody>
            {items.map((item) => (
              <tr
                key={item.lineNumber}
                onClick={() =>
                  item.sourcePage != null && onJumpToPage?.(Math.max(0, item.sourcePage - 1))
                }
                className="border-b border-border last:border-b-0 hover:bg-muted"
              >
                <td className="px-3 py-2.5 text-[13px] text-foreground">
                  {item.description ?? <span className="text-muted-foreground italic">—</span>}
                </td>
                <td className="px-3 py-2.5 text-right font-mono text-[12.5px]">{item.quantity ?? "—"}</td>
                <td className="px-3 py-2.5 text-right font-mono text-[12.5px]">{item.unitPrice ?? "—"}</td>
                <td className="px-3 py-2.5 text-right font-mono text-[12.5px]">{item.lineTotal ?? "—"}</td>
                <td className="px-3 py-2.5 text-right">
                  {item.confidence != null ? (
                    <Badge variant={item.confidence < 0.8 ? "gold" : "muted"}>
                      {Math.round(item.confidence * 100)}%
                    </Badge>
                  ) : (
                    <span className="text-[12px] text-muted-foreground">—</span>
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
