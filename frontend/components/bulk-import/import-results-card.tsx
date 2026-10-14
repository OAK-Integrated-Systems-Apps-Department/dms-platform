"use client"

import { useState } from "react"
import {
  AlertTriangle,
  CheckCircle2,
  Download,
  Loader2,
  RotateCcw,
  XCircle,
} from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { friendlyMessage } from "@/lib/api-error"
import {
  downloadFailedRowsTemplate,
  hasFailedRows,
  saveDownloadedFile,
  type BulkImportStatusResponseDto,
  type TemplateFormat,
} from "@/lib/bulk-imports/api"

import { FailedRowsTable } from "./failed-rows-table"
import { InlineError, ProgressBar, StatTile, StepCard } from "./step-card"

interface ImportResultsCardProps {
  batch: BulkImportStatusResponseDto
  documentTypeName: string | null
  onStartOver: () => void
}

/** Summary of a finished import: how many documents were created, how many rows failed, and which ones. */
export function ImportResultsCard({
  batch,
  documentTypeName,
  onStartOver,
}: ImportResultsCardProps) {
  const { title, badge } = describe(batch)
  const created = `${batch.successCount} ${batch.successCount === 1 ? "document" : "documents"} created`
  const failed = `${batch.failureCount} ${batch.failureCount === 1 ? "row" : "rows"} failed`

  return (
    <StepCard
      icon={
        batch.status === "completed"
          ? CheckCircle2
          : batch.status === "failed"
            ? XCircle
            : AlertTriangle
      }
      title={title}
      description={`${created}, ${failed}.`}
    >
      <dl className="mb-4 grid gap-3 sm:grid-cols-3">
        <StatTile label="Document type">{documentTypeName ?? "—"}</StatTile>
        <StatTile label="Documents created">
          {batch.successCount} of {batch.totalRecords}
        </StatTile>
        <StatTile label="Status">{badge}</StatTile>
      </dl>
      <div className="mb-5">
        <ProgressBar
          value={batch.successCount}
          max={batch.totalRecords}
          label="Documents created"
        />
      </div>

      <OcrProgressPanel batch={batch} />

      {hasFailedRows(batch.status) && (
        <>
          <FailedRowsTable batchId={batch.batchId} />
          <FixFailedRows batchId={batch.batchId} />
        </>
      )}

      <Button onClick={onStartOver}>
        <RotateCcw />
        Start a new import
      </Button>
    </StepCard>
  )
}

/**
 * "OCR pending / done / failed" for an import that asked for OCR.
 *
 * <p>Renders nothing at all when `batch.ocr` is null - that is "this import
 * has no OCR", which is different from "its OCR has not started", and a row
 * of zeroes would say the second when the first is true.
 *
 * <p>Progress is measured against `queued`, not `totalRecords`. The per-batch
 * cap and the tenant's queue limit both stop short of every file, so an import
 * of 5,000 files with a cap of 500 is legitimately 100% done at 500 - showing
 * it as 10% would report healthy work as stalled.
 */
function OcrProgressPanel({ batch }: { batch: BulkImportStatusResponseDto }) {
  const ocr = batch.ocr
  if (!ocr) return null

  const skipped = ocr.skippedFilenames ?? []
  const skippedTotal = ocr.skippedTotal ?? skipped.length
  const skippedMoreCount = skippedTotal - skipped.length
  return (
    <div className="mb-5">
      <dl className="mb-3 grid gap-3 sm:grid-cols-3">
        <StatTile label="OCR waiting">{ocr.pending}</StatTile>
        <StatTile label="OCR done">{ocr.completed}</StatTile>
        <StatTile label="OCR failed">{ocr.failed}</StatTile>
      </dl>
      <ProgressBar
        value={ocr.completed + ocr.failed}
        max={ocr.queued}
        label="OCR finished"
      />
      {skippedTotal > 0 && (
        <div className="mt-3 rounded-lg border border-border bg-muted/30 px-3.5 py-3">
          <p className="text-[12.5px] leading-[1.5] text-foreground">
            <span className="font-semibold">
              {skippedTotal} {skippedTotal === 1 ? "file was" : "files were"} imported
              without OCR
            </span>{" "}
            because this import reached its OCR limit. They are in the DMS and
            complete — only OCR was skipped. Open any of them and choose{" "}
            <span className="font-medium">Run OCR</span> to extract its fields.
          </p>
          {/*
            Named, not just counted. A count tells someone that something was
            skipped without telling them which, which leaves them to diff an
            import of thousands of rows by hand.

            Scrollable rather than paginated: this is a reference list someone
            copies from, and a cap with its own "show more" would be a second
            thing to click through for no benefit. The list itself is still
            capped server-side (skippedFilenames), so skippedMoreCount covers
            the rest with a count rather than silently truncating.
          */}
          <ul className="mt-2 max-h-40 overflow-y-auto font-mono text-[11.5px] leading-[1.6] text-muted-foreground">
            {skipped.map((filename) => (
              <li key={filename}>{filename}</li>
            ))}
          </ul>
          {skippedMoreCount > 0 && (
            <p className="mt-1 text-[11.5px] leading-[1.5] text-muted-foreground">
              + {skippedMoreCount} more not shown
            </p>
          )}
        </div>
      )}
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

/**
 * Fix and resubmit: the failed rows come back as a ready-to-upload
 * template (with a column saying what was wrong); correct them and start a
 * new import with it and just those rows' files.
 */
function FixFailedRows({ batchId }: { batchId: string }) {
  const [downloading, setDownloading] = useState<TemplateFormat | null>(null)
  const [error, setError] = useState<string | null>(null)

  async function download(format: TemplateFormat) {
    setDownloading(format)
    setError(null)
    try {
      saveDownloadedFile(await downloadFailedRowsTemplate(batchId, format))
    } catch (e) {
      setError(friendlyMessage(e, "Could not download the failed rows."))
    } finally {
      setDownloading(null)
    }
  }

  return (
    <section className="mb-5 rounded-md border border-dashed border-border px-4 py-3">
      <h3 className="mb-1 text-[13px] font-semibold">
        Fix and import the failed rows again
      </h3>
      <p className="mb-3 text-[12.5px] leading-[1.6] text-muted-foreground">
        Download the failed rows as a template - each row as you uploaded it,
        with what went wrong in its last column. Correct them, then start a new
        import with that file and the files those rows list.
      </p>
      <div className="flex flex-wrap gap-2">
        {(["xlsx", "csv"] as const).map((format) => (
          <Button
            key={format}
            variant="outline"
            size="sm"
            onClick={() => void download(format)}
            disabled={downloading !== null}
          >
            {downloading === format ? (
              <Loader2 className="animate-spin" />
            ) : (
              <Download />
            )}
            {format === "xlsx" ? "Failed rows (Excel)" : "Failed rows (CSV)"}
          </Button>
        ))}
      </div>
      {error && <InlineError>{error}</InlineError>}
    </section>
  )
}
