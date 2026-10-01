"use client"

import { useState } from "react"
import { Download, Loader2 } from "lucide-react"
import { toast } from "sonner"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { friendlyMessage } from "@/lib/api-error"
import { saveDownloadedFile } from "@/lib/bulk-imports/api"
import { downloadExport } from "@/lib/export-jobs/api"
import {
  EXPORT_JOB_STATUS_BADGE_VARIANT,
  EXPORT_JOB_STATUS_LABELS,
  EXPORT_TYPE_LABELS,
} from "@/lib/export-jobs/export-job-badges"
import { formatBytes } from "@/lib/quarantine/quarantine-badges"
import { formatDateTime } from "@/lib/utils"

import type { ExportJobDto } from "@/lib/export-jobs/api"

interface ExportJobsTableProps {
  rows: ExportJobDto[]
  /** True when a status filter is active - changes the empty-state wording. */
  filtered: boolean
}

const TH = "px-3 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase"

/**
 * The caller's export jobs, with a download for each finished one until it
 * expires. The list polls on its own while an export is being prepared
 * (see useMyExportJobs).
 */
export function ExportJobsTable({ rows, filtered }: ExportJobsTableProps) {
  return (
    <div className="overflow-hidden rounded-lg border border-border bg-card shadow-[var(--ledger-shadow)]">
      <div className="overflow-x-auto">
        <table className="w-full min-w-[860px] border-collapse">
          <thead>
            <tr className="border-b border-border">
              <th className={TH}>Requested</th>
              <th className={TH}>Export</th>
              <th className={TH}>Status</th>
              <th className={TH}>Size</th>
              <th className={TH}>Available Until</th>
              <th className={TH}>
                <span className="sr-only">Download</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && (
              <tr>
                <td colSpan={6} className="px-3 py-10 text-center text-[13px] text-muted-foreground">
                  {filtered ? "No exports with this status." : "You haven't requested any exports yet."}
                </td>
              </tr>
            )}
            {rows.map((row) => (
              <tr key={row.exportJobId} className="border-b border-border align-top last:border-b-0">
                <td className="px-3 py-3 font-mono text-[12.5px] text-muted-foreground">
                  {formatDateTime(row.createdAt)}
                </td>
                <td className="px-3 py-3">
                  <p className="text-[13.5px] font-semibold text-foreground">{EXPORT_TYPE_LABELS[row.exportType]}</p>
                  <p className="text-[12px] text-muted-foreground">
                    {row.documentCount} {row.documentCount === 1 ? "document" : "documents"} · metadata as{" "}
                    {row.metadataFormat === "csv" ? "CSV" : "Excel"}
                  </p>
                </td>
                <td className="px-3 py-3">
                  <Badge variant={EXPORT_JOB_STATUS_BADGE_VARIANT[row.status]} className="gap-1.5 rounded-md">
                    {row.status === "processing" ? (
                      <Loader2 className="size-3 animate-spin" aria-hidden="true" />
                    ) : (
                      <span className="size-[5px] rounded-full bg-current" />
                    )}
                    {EXPORT_JOB_STATUS_LABELS[row.status]}
                  </Badge>
                  {row.failureReason && (
                    <p
                      className={`mt-1 max-w-[260px] text-[12px] leading-[1.5] ${
                        row.status === "failed" ? "text-[var(--rust)]" : "text-muted-foreground"
                      }`}
                    >
                      {row.failureReason}
                    </p>
                  )}
                </td>
                <td className="px-3 py-3 font-mono text-[12.5px] text-muted-foreground">
                  {row.fileSizeBytes !== null ? formatBytes(row.fileSizeBytes) : "—"}
                </td>
                <td className="px-3 py-3 font-mono text-[12.5px] text-muted-foreground">
                  {row.expiresAt ? formatDateTime(row.expiresAt) : "—"}
                </td>
                <td className="px-3 py-3 text-right">
                  {row.downloadAvailable && <DownloadButton exportJobId={row.exportJobId} />}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function DownloadButton({ exportJobId }: { exportJobId: string }) {
  const [downloading, setDownloading] = useState(false)

  async function handleClick() {
    setDownloading(true)
    try {
      saveDownloadedFile(await downloadExport(exportJobId))
    } catch (error) {
      toast.error(friendlyMessage(error, "Could not download the export."))
    } finally {
      setDownloading(false)
    }
  }

  return (
    <Button variant="outline" size="sm" onClick={() => void handleClick()} disabled={downloading}>
      {downloading ? <Loader2 className="animate-spin" /> : <Download />}
      Download
    </Button>
  )
}
