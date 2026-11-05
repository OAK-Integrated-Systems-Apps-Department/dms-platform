"use client"

import { AlertTriangle, CheckCircle2, ChevronRight, XCircle } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { formatDateTime } from "@/lib/utils"

import type { ReviewQueueItem } from "@/lib/documents/api"

interface ReviewQueueTableProps {
  rows: ReviewQueueItem[]
  onOpen: (row: ReviewQueueItem) => void
}

/**
 * Document / Why / Flagged at, newest first. Deliberately NOT sortable:
 * the backend orders by `created_at DESC` through V41's partial index,
 * and a client-side re-sort of one 25-row page would reorder a slice
 * rather than the queue, which reads as a sorted queue and isn't one.
 *
 * The "Why" column is the reason this screen exists. It renders the
 * server's `summary` sentence rather than the `reasons` codes: the codes
 * are for filtering, the sentence is for a human deciding whether to open
 * the document. Everything else on the row is there to identify the
 * document, not to diagnose it.
 */
export function ReviewQueueTable({ rows, onOpen }: ReviewQueueTableProps) {
  return (
    <div className="overflow-hidden rounded-lg border border-border bg-card shadow-[var(--ledger-shadow)]">
      <div className="overflow-x-auto">
        <table className="tbl-card tbl-last-fit w-full min-w-[820px] border-collapse">
          <thead>
            <tr className="border-b border-border">
              <th className="px-3 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
                Document
              </th>
              <th className="px-3 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
                Why it needs review
              </th>
              <th className="px-3 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
                Status
              </th>
              <th className="px-3 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
                Uploaded
              </th>
              <th className="px-3 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase" />
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && (
              <tr>
                <td colSpan={5} className="h-[max(160px,calc(100svh-340px))] px-3 py-10 text-center text-[13px] text-muted-foreground">
                  Nothing needs review. Every document processed cleanly.
                </td>
              </tr>
            )}
            {rows.map((row) => {
              const failed = row.ocrStatus === "failed"
              // On the queue, every row is here because requiresReview is
              // still true (or OCR failed) - see findReviewQueue. On Saved
              // Reviews, which lists anything EVER reviewed regardless of
              // outcome, a row can genuinely be settled already: this is
              // what distinguishes "still needs another look" from "done."
              const resolved = !failed && !row.requiresReview
              return (
                <tr
                  key={row.versionId}
                  onClick={() => onOpen(row)}
                  className="cursor-pointer border-b border-border last:border-b-0 hover:bg-muted"
                >
                  <td className="px-3 py-3 text-[13px]">
                    <div className="flex flex-col">
                      <span className="text-[13.5px] font-semibold text-primary">{row.title}</span>
                      <span className="text-[12px] text-muted-foreground">{row.originalFilename}</span>
                    </div>
                  </td>
                  <td className="max-w-[420px] px-3 py-3 text-[13px] text-foreground">
                    <div className="flex items-start gap-2">
                      {failed ? (
                        <XCircle size={14} className="mt-[3px] shrink-0 text-[var(--rust)]" />
                      ) : resolved ? (
                        <CheckCircle2 size={14} className="mt-[3px] shrink-0 text-[var(--gold-foreground)]" />
                      ) : (
                        <AlertTriangle size={14} className="mt-[3px] shrink-0 text-[var(--gold-foreground)]" />
                      )}
                      <span>{row.summary}</span>
                    </div>
                  </td>
                  <td className="px-3 py-3">
                    <Badge variant={failed ? "rust" : resolved ? "muted" : "gold"}>
                      {failed ? "OCR failed" : resolved ? "Resolved" : "Needs review"}
                    </Badge>
                  </td>
                  <td className="px-3 py-3 font-mono text-[12.5px] text-muted-foreground">
                    {formatDateTime(row.createdAt)}
                  </td>
                  <td className="px-3 py-3 text-right">
                    <ChevronRight size={15} className="inline text-muted-foreground" />
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </div>
  )
}
