"use client"

import { AlertTriangle, Ban, CheckCircle2, RotateCcw, XCircle } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { hasFailedRows, isFinished, type BulkImportStatusResponseDto } from "@/lib/bulk-imports/api"
import { useImportDefaults } from "@/lib/bulk-imports/use-import-defaults"

import { FailedRowsTable } from "./failed-rows-table"
import { StepCard } from "./step-card"

interface ImportResultsCardProps {
  batch: BulkImportStatusResponseDto
  documentTypeName: string | null
  onStartOver: () => void
}

/**
 * Summary of a finished import: how many documents were created, how many
 * files failed, and which ones. A cancelled import gets the same card, minus
 * the counts' wording - nothing was processed - and says whether it was
 * cancelled by the user or expired after a period with no uploads.
 */
export function ImportResultsCard({ batch, documentTypeName, onStartOver }: ImportResultsCardProps) {
  const { title, badge } = describe(batch)
  const created = `${batch.successCount} ${batch.successCount === 1 ? "document" : "documents"} created`
  const failed = `${batch.failureCount} ${batch.failureCount === 1 ? "file" : "files"} failed`
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

      {batch.successCount > 0 && isFinished(batch.status) && (
        <EmptyRequiredFieldsNote documentTypeId={batch.documentTypeId} />
      )}

      {hasFailedRows(batch.status) && <FailedRowsTable batchId={batch.batchId} />}

      <Button onClick={onStartOver}>
        <RotateCcw />
        Start a new import
      </Button>
    </StepCard>
  )
}

/**
 * The type's required fields with no default, which the created documents
 * were left without. Read from the type's current defaults (the batch's
 * schema version isn't in the status response); left out if they can't be
 * loaded, e.g. for a type that has since been deactivated.
 */
function EmptyRequiredFieldsNote({ documentTypeId }: { documentTypeId: string }) {
  const { data } = useImportDefaults(documentTypeId)
  const missing = data?.mandatoryFieldsWithoutDefault ?? []
  if (missing.length === 0) return null

  return (
    <p className="mb-5 text-[12.5px] text-muted-foreground">
      These required fields were left empty and need filling in on the documents:{" "}
      {missing.map((field) => field.label).join(", ")}.
    </p>
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
