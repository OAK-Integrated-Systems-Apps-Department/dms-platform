"use client"

import { useState } from "react"
import { Download, Loader2 } from "lucide-react"
import { toast } from "sonner"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { saveDownloadedFile } from "@/lib/download-file"
import { formatBytes } from "@/lib/quarantine/quarantine-badges"
import { downloadWatermark } from "@/lib/watermarks/api"
import { describeWatermarkError } from "@/lib/watermarks/watermark-errors"
import {
  WATERMARK_JOB_STATUS_BADGE_VARIANT,
  WATERMARK_JOB_STATUS_LABELS,
} from "@/lib/watermarks/watermark-badges"
import { formatDateTime } from "@/lib/utils"

import type { WatermarkJobDto } from "@/lib/watermarks/api"

interface WatermarkJobsTableProps {
  rows: WatermarkJobDto[]
  /** True when a status filter is active - changes the empty-state wording. */
  filtered: boolean
}

const TH =
  "px-3 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase"

/**
 * The caller's watermarked copies, with a download for each finished one
 * until it expires. Mirrors ExportJobsTable deliberately - the two lists do
 * the same job and a user should not have to learn two layouts.
 *
 * The watermark TEXT is the identifying column rather than the document
 * title: the job carries the resolved text (which is what is actually on
 * the page, frozen at request time) but not the document's name, and
 * showing the text is both honest and more useful than a lookup that could
 * disagree with the copy.
 */
export function WatermarkJobsTable({ rows, filtered }: WatermarkJobsTableProps) {
  return (
    <div className="overflow-hidden rounded-lg border border-border bg-card shadow-[var(--ledger-shadow)]">
      <div className="overflow-x-auto">
        <table className="tbl-card w-full min-w-[820px] border-collapse">
          <thead>
            <tr className="border-b border-border">
              <th className={TH}>Requested</th>
              <th className={TH}>Watermark</th>
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
                  {filtered
                    ? "No watermarked copies with this status."
                    : "You haven't made any watermarked copies yet."}
                </td>
              </tr>
            )}
            {rows.map((row) => (
              <tr key={row.jobId} className="border-b border-border align-top last:border-b-0">
                <td className="px-3 py-3 font-mono text-[12.5px] text-muted-foreground">
                  {formatDateTime(row.createdAt)}
                </td>
                <td className="px-3 py-3">
                  <p className="max-w-[280px] break-words text-[13.5px] font-semibold text-foreground">
                    {row.text}
                  </p>
                  <p className="font-mono text-[11.5px] text-muted-foreground">
                    {row.jobId.slice(0, 8)}
                  </p>
                </td>
                <td className="px-3 py-3">
                  <Badge
                    variant={WATERMARK_JOB_STATUS_BADGE_VARIANT[row.status]}
                    className="gap-1.5 rounded-btn"
                  >
                    {row.status === "processing" ? (
                      <Loader2 className="size-3 animate-spin" aria-hidden="true" />
                    ) : (
                      <span className="size-[5px] rounded-full bg-current" />
                    )}
                    {WATERMARK_JOB_STATUS_LABELS[row.status]}
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
                  {row.outputSizeBytes !== null ? formatBytes(row.outputSizeBytes) : "—"}
                </td>
                <td className="px-3 py-3 font-mono text-[12.5px] text-muted-foreground">
                  {row.expiresAt ? formatDateTime(row.expiresAt) : "—"}
                </td>
                <td className="px-3 py-3 text-right">
                  {row.downloadAvailable && <DownloadButton jobId={row.jobId} />}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function DownloadButton({ jobId }: { jobId: string }) {
  const [downloading, setDownloading] = useState(false)

  async function handleClick() {
    setDownloading(true)
    try {
      saveDownloadedFile(await downloadWatermark(jobId))
    } catch (error) {
      toast.error(describeWatermarkError(error).message)
    } finally {
      setDownloading(false)
    }
  }

  return (
    <Button type="button" variant="outline" onClick={handleClick} disabled={downloading}>
      {downloading ? (
        <Loader2 className="size-3.5 animate-spin" aria-hidden="true" />
      ) : (
        <Download size={14} aria-hidden="true" />
      )}
      Download
    </Button>
  )
}
