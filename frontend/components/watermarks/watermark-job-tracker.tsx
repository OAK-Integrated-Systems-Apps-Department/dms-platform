"use client"

import { useState } from "react"
import { AlertTriangle, CheckCircle2, Download, Loader2 } from "lucide-react"
import { toast } from "sonner"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { saveDownloadedFile } from "@/lib/download-file"
import { downloadWatermark } from "@/lib/watermarks/api"
import { describeWatermarkError } from "@/lib/watermarks/watermark-errors"
import {
  WATERMARK_JOB_STATUS_BADGE_VARIANT,
  WATERMARK_JOB_STATUS_LABELS,
} from "@/lib/watermarks/watermark-badges"
import { useWatermarkJob } from "@/lib/watermarks/use-watermark-jobs"
import { formatDateTime } from "@/lib/utils"

import type { WatermarkJobDto } from "@/lib/watermarks/api"

interface WatermarkJobTrackerProps {
  /** The job as the 202 returned it - shown immediately, then polled. */
  job: WatermarkJobDto
  onClose: () => void
}

/**
 * What the dialog shows after the request is accepted: the copy being made,
 * then a download.
 *
 * The 202's own body seeds the view so there is never a blank moment, and
 * polling (see `useWatermarkJob`) replaces it as the job moves. Polling
 * stops on any terminal state - a failed job is not retried silently,
 * because the reason is usually about the document itself and asking again
 * would produce the same answer.
 */
export function WatermarkJobTracker({ job: initial, onClose }: WatermarkJobTrackerProps) {
  const { data } = useWatermarkJob(initial.jobId)
  const job = data ?? initial
  const [downloading, setDownloading] = useState(false)

  async function handleDownload() {
    setDownloading(true)
    try {
      saveDownloadedFile(await downloadWatermark(job.jobId))
    } catch (error) {
      // 410 (expired) and 400 (not ready yet) both land here with the
      // server's own wording, which already says what to do about it.
      toast.error(describeWatermarkError(error).message)
    } finally {
      setDownloading(false)
    }
  }

  const inFlight = job.status === "pending" || job.status === "processing"

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center gap-2.5">
        <Badge variant={WATERMARK_JOB_STATUS_BADGE_VARIANT[job.status]} className="gap-1.5 rounded-btn">
          {inFlight ? (
            <Loader2 className="size-3 animate-spin" aria-hidden="true" />
          ) : job.status === "completed" ? (
            <CheckCircle2 className="size-3" aria-hidden="true" />
          ) : (
            <span className="size-[5px] rounded-full bg-current" />
          )}
          {WATERMARK_JOB_STATUS_LABELS[job.status]}
        </Badge>
        {inFlight && (
          <span className="text-[12.5px] text-muted-foreground">
            This usually takes a second or two.
          </span>
        )}
      </div>

      {/* aria-live so a screen reader hears the copy become ready without
          the user having to go looking for it. */}
      <p className="sr-only" aria-live="polite">
        Watermark {WATERMARK_JOB_STATUS_LABELS[job.status].toLowerCase()}
      </p>

      <dl className="flex flex-col gap-2 rounded-lg border border-border bg-muted/20 px-3.5 py-3 text-[12.5px]">
        <div className="flex gap-2">
          <dt className="w-[92px] shrink-0 text-muted-foreground">Watermark</dt>
          <dd className="min-w-0 break-words font-medium text-foreground">{job.text}</dd>
        </div>
        <div className="flex gap-2">
          <dt className="w-[92px] shrink-0 text-muted-foreground">Requested</dt>
          <dd className="font-mono text-muted-foreground">{formatDateTime(job.createdAt)}</dd>
        </div>
        {job.expiresAt && (
          <div className="flex gap-2">
            <dt className="w-[92px] shrink-0 text-muted-foreground">Available until</dt>
            <dd className="font-mono text-muted-foreground">{formatDateTime(job.expiresAt)}</dd>
          </div>
        )}
      </dl>

      {job.failureReason && (
        <p
          role="alert"
          className="flex items-start gap-1.5 rounded-btn border border-[var(--rust)] bg-[var(--rust-tint)] px-3.5 py-2.5 text-[12.5px] leading-[1.5] text-[var(--rust)]"
        >
          <AlertTriangle size={14} className="mt-0.5 shrink-0" aria-hidden="true" />
          {job.failureReason}
        </p>
      )}

      <div className="flex items-center justify-end gap-2.5">
        <Button type="button" variant="outline" onClick={onClose}>
          Close
        </Button>
        {job.downloadAvailable && (
          <Button type="button" onClick={handleDownload} disabled={downloading}>
            {downloading ? (
              <Loader2 className="size-3.5 animate-spin" aria-hidden="true" />
            ) : (
              <Download size={14} aria-hidden="true" />
            )}
            Download copy
          </Button>
        )}
      </div>
    </div>
  )
}
