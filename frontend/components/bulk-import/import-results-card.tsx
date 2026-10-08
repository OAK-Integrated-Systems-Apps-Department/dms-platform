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
