"use client"

import { useState } from "react"
import { AlertTriangle, Ban, CheckCircle2, Download, Loader2, RotateCcw, XCircle } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { friendlyMessage } from "@/lib/api-error"
import {
  downloadFailedRowsTemplate,
  hasFailedRows,
  saveDownloadedFile,
  type BulkImportStatusResponseDto,
} from "@/lib/bulk-imports/api"

import { FailedRowsTable } from "./failed-rows-table"
import { InlineError, StepCard } from "./step-card"

interface ImportResultsCardProps {
  batch: BulkImportStatusResponseDto
  documentTypeName: string | null
  onStartOver: () => void
}

/**
 * Summary of a finished import: how many documents were created, how many
 * rows failed, and which ones. A cancelled import gets the same card, minus
 * the counts' wording - nothing was processed - and says whether it was
 * cancelled by the user or expired after a period with no uploads.
 */
export function ImportResultsCard({ batch, documentTypeName, onStartOver }: ImportResultsCardProps) {
  const { title, badge } = describe(batch)
  const created = `${batch.successCount} ${batch.successCount === 1 ? "document" : "documents"} created`
  const failed = `${batch.failureCount} ${batch.failureCount === 1 ? "row" : "rows"} failed`
  const description =
    batch.status !== "cancelled"
      ? `${created}, ${failed}.`
      : batch.cancelReason === "expired"
        ? "This import expired after a period with no uploads and was cancelled automatically."
        : "This import was cancelled before processing; no documents were created."

  return (
    <StepCard title={title} description={description}>
      <dl className="mb-5 grid gap-x-8 gap-y-3 text-[13px] sm:grid-cols-3">
        <div>
          <dt className="text-[12px] text-muted-foreground">Document type</dt>
          <dd className="font-medium">{documentTypeName ?? "—"}</dd>
        </div>
        <div>
          <dt className="text-[12px] text-muted-foreground">Documents created</dt>
          <dd className="font-medium">
            {batch.successCount} of {batch.totalRecords}
          </dd>
        </div>
        <div>
          <dt className="text-[12px] text-muted-foreground">Status</dt>
          <dd className="font-medium">{badge}</dd>
        </div>
      </dl>

      {hasFailedRows(batch.status) && (
        <>
          <DownloadFailedRows batchId={batch.batchId} />
          <FailedRowsTable batchId={batch.batchId} />
        </>
      )}

      <Button onClick={onStartOver}>
        <RotateCcw />
        Start a new import
      </Button>
    </StepCard>
  )
}

/** The failed rows as a fixable template - uploading it again starts a resubmit that reuses kept files. */
function DownloadFailedRows({ batchId }: { batchId: string }) {
  const [downloading, setDownloading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function handleDownload() {
    setDownloading(true)
    setError(null)
    try {
      saveDownloadedFile(await downloadFailedRowsTemplate(batchId))
    } catch (err) {
      setError(friendlyMessage(err, "Could not download the failed rows."))
    } finally {
      setDownloading(false)
    }
  }

  return (
    <div className="mb-5">
      <Button variant="outline" onClick={handleDownload} disabled={downloading}>
        {downloading ? <Loader2 className="animate-spin" /> : <Download />}
        {downloading ? "Downloading…" : "Download failed rows"}
      </Button>
      <p className="mt-2 text-[12.5px] text-muted-foreground">
        Fix the rows in this file and upload it as a new import. Files for rows that only had data problems are kept
        for 24 hours, so you won&apos;t need to upload them again.
      </p>
      {error && <InlineError>{error}</InlineError>}
    </div>
  )
}

function describe(batch: BulkImportStatusResponseDto) {
  switch (batch.status) {
    case "completed":
      return {
        title: "Import complete",
        badge: (
          <Badge variant="default">
            <CheckCircle2 aria-hidden="true" />
            Completed
          </Badge>
        ),
      }
    case "completed_with_errors":
      return {
        title: "Import finished with errors",
        badge: (
          <Badge variant="gold">
            <AlertTriangle aria-hidden="true" />
            Completed with errors
          </Badge>
        ),
      }
    case "cancelled":
      return {
        title: "Import cancelled",
        badge: (
          <Badge variant="outline">
            <Ban aria-hidden="true" />
            Cancelled
          </Badge>
        ),
      }
    default:
      return {
        title: "Import failed",
        badge: (
          <Badge variant="rust">
            <XCircle aria-hidden="true" />
            Failed
          </Badge>
        ),
      }
  }
}
