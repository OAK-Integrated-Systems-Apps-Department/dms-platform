"use client"

import { Badge } from "@/components/ui/badge"
import {
  EXPORT_JOB_STATUS_BADGE_VARIANT,
  EXPORT_JOB_STATUS_LABELS,
  EXPORT_TYPE_LABELS,
} from "@/lib/export-jobs/export-job-badges"
import { formatDateTime } from "@/lib/utils"

import type { ExportJobDto } from "@/lib/export-jobs/api"

interface ExportJobsTableProps {
  rows: ExportJobDto[]
  /** True when a status filter is active - changes the empty-state wording. */
  filtered: boolean
}

const TH = "px-3 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase"

/**
 * The caller's export jobs. Read-only for now: there is no download button
 * yet because the download endpoint arrives with export generation itself
 * (Phase 4) - "Ready" rows say so instead of offering a dead link.
 */
export function ExportJobsTable({ rows, filtered }: ExportJobsTableProps) {
  return (
    <div className="overflow-hidden rounded-lg border border-border bg-card shadow-[var(--ledger-shadow)]">
      <div className="overflow-x-auto">
        <table className="w-full min-w-[760px] border-collapse">
          <thead>
            <tr className="border-b border-border">
              <th className={TH}>Requested</th>
              <th className={TH}>Type</th>
              <th className={TH}>Status</th>
              <th className={TH}>Last Updated</th>
              <th className={TH}>Available Until</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && (
              <tr>
                <td colSpan={5} className="px-3 py-10 text-center text-[13px] text-muted-foreground">
                  {filtered ? "No exports with this status." : "You haven't requested any exports yet."}
                </td>
              </tr>
            )}
            {rows.map((row) => (
              <tr key={row.exportJobId} className="border-b border-border last:border-b-0">
                <td className="px-3 py-3 font-mono text-[12.5px] text-muted-foreground">
                  {formatDateTime(row.createdAt)}
                </td>
                <td className="px-3 py-3 text-[13.5px] font-semibold text-foreground">
                  {EXPORT_TYPE_LABELS[row.exportType]}
                </td>
                <td className="px-3 py-3">
                  <Badge variant={EXPORT_JOB_STATUS_BADGE_VARIANT[row.status]} className="gap-1.5 rounded-md">
                    <span className="size-[5px] rounded-full bg-current" />
                    {EXPORT_JOB_STATUS_LABELS[row.status]}
                  </Badge>
                </td>
                <td className="px-3 py-3 font-mono text-[12.5px] text-muted-foreground">
                  {formatDateTime(row.updatedAt)}
                </td>
                <td className="px-3 py-3 font-mono text-[12.5px] text-muted-foreground">
                  {row.expiresAt ? formatDateTime(row.expiresAt) : "—"}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
