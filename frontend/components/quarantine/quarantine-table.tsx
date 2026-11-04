"use client"

import { ChevronRight } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import {
  QUARANTINE_STATUS_BADGE_VARIANT,
  QUARANTINE_STATUS_LABELS,
  formatBytes,
} from "@/lib/quarantine/quarantine-badges"
import { formatDateTime } from "@/lib/utils"

import type { QuarantineRecordDto } from "@/lib/quarantine/api"

interface QuarantineTableProps {
  rows: QuarantineRecordDto[]
  onView: (row: QuarantineRecordDto) => void
}

const TH = "px-3 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase"

/**
 * Detected / File / Threat / Size / Uploaded By / Status, newest first
 * (server-sorted, like the audit log). Every row opens the detail page,
 * where the scan result and the review actions live. There is deliberately
 * nothing here - or anywhere - that opens or downloads the quarantined file.
 */
export function QuarantineTable({ rows, onView }: QuarantineTableProps) {
  return (
    <div className="overflow-hidden rounded-lg border border-border bg-card shadow-[var(--ledger-shadow)]">
      <div className="overflow-x-auto">
        <table className="tbl-card w-full min-w-[880px] border-collapse">
          <thead>
            <tr className="border-b border-border">
              <th className={TH}>Detected</th>
              <th className={TH}>File</th>
              <th className={TH}>Threat</th>
              <th className={TH}>Size</th>
              <th className={TH}>Uploaded By</th>
              <th className={TH}>Status</th>
              <th className={TH} />
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && (
              <tr>
                <td colSpan={7} className="h-[max(160px,calc(100svh-380px))] px-3 py-10 text-center text-[13px] text-muted-foreground">
                  No quarantined files match your search or filters.
                </td>
              </tr>
            )}
            {rows.map((row) => (
              <tr
                key={row.quarantineId}
                onClick={() => onView(row)}
                className="cursor-pointer border-b border-border last:border-b-0 hover:bg-muted"
              >
                <td className="px-3 py-3 font-mono text-[12.5px] text-muted-foreground">
                  {formatDateTime(row.detectedAt)}
                </td>
                <td className="px-3 py-3 text-[13.5px] font-semibold text-primary">{row.originalFilename}</td>
                <td className="px-3 py-3 text-[13px] text-foreground">{row.threatName}</td>
                <td className="px-3 py-3 text-[13px] text-muted-foreground">{formatBytes(row.sizeBytes)}</td>
                <td className="px-3 py-3 text-[13px] text-foreground">{row.uploadedBy?.displayName ?? "—"}</td>
                <td className="px-3 py-3">
                  <Badge variant={QUARANTINE_STATUS_BADGE_VARIANT[row.status]} className="gap-1.5 rounded-btn">
                    <span className="size-[5px] rounded-full bg-current" />
                    {QUARANTINE_STATUS_LABELS[row.status]}
                  </Badge>
                </td>
                <td className="px-3 py-3 text-right">
                  <ChevronRight size={15} className="inline text-muted-foreground" />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
