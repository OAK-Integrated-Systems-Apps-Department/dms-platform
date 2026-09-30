"use client"

import { AlertTriangle, CheckCircle2, RotateCcw, XCircle } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import type { BulkImportStatusResponseDto } from "@/lib/bulk-imports/api"

import { StepCard } from "./step-card"

interface ImportResultsCardProps {
  batch: BulkImportStatusResponseDto
  documentTypeName: string | null
  onStartOver: () => void
}

/** Summary of a finished import: how many documents were created and how many rows failed. */
export function ImportResultsCard({ batch, documentTypeName, onStartOver }: ImportResultsCardProps) {
  const { title, badge } = describe(batch)
  const created = `${batch.successCount} ${batch.successCount === 1 ? "document" : "documents"} created`
  const failed = `${batch.failureCount} ${batch.failureCount === 1 ? "row" : "rows"} failed`

  return (
    <StepCard title={title} description={`${created}, ${failed}.`}>
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
