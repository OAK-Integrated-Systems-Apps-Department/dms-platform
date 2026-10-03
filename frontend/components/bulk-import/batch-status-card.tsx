"use client"

import { useState } from "react"
import { Clock, Loader2, Upload, X } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import type { BulkImportStatusResponseDto } from "@/lib/bulk-imports/api"

import { StepCard } from "./step-card"
import { CancelImportDialog } from "./upload-files-step"

interface BatchStatusCardProps {
  batch: BulkImportStatusResponseDto
  documentTypeName: string | null
  /** Only offered for an `uploading` batch. */
  onContinue?: () => void
  onCancel: () => Promise<string | null>
}

/**
 * The caller's existing import, found on load (or just submitted): resume
 * or cancel an `uploading` batch, cancel a `queued` one, or just watch a
 * `running` one - the server refuses to cancel that.
 */
export function BatchStatusCard({ batch, documentTypeName, onContinue, onCancel }: BatchStatusCardProps) {
  const [cancelOpen, setCancelOpen] = useState(false)
  const canCancel = batch.status === "uploading" || batch.status === "queued"

  const { title, description, badge } = describe(batch)

  return (
    <StepCard title={title} description={description}>
      <dl className="mb-5 grid gap-x-8 gap-y-3 text-[13px] sm:grid-cols-3">
        <div>
          <dt className="text-[12px] text-muted-foreground">Document type</dt>
          <dd className="font-medium">{documentTypeName ?? "—"}</dd>
        </div>
        <div>
          <dt className="text-[12px] text-muted-foreground">Started</dt>
          <dd className="font-medium">{formatDateTime(batch.createdAt)}</dd>
        </div>
        <div>
          <dt className="text-[12px] text-muted-foreground">Files</dt>
          <dd className="flex items-center gap-2 font-medium">
            {batch.filesReceived} of {batch.totalRecords} received
            {badge}
          </dd>
        </div>
      </dl>

      <div className="flex flex-wrap gap-2.5">
        {batch.status === "uploading" && onContinue && (
          <Button onClick={onContinue}>
            <Upload />
            Continue uploading
          </Button>
        )}
        {canCancel && (
          <Button variant="outline" onClick={() => setCancelOpen(true)}>
            <X />
            Cancel import
          </Button>
        )}
      </div>

      <CancelImportDialog open={cancelOpen} onOpenChange={setCancelOpen} onConfirm={onCancel} />
    </StepCard>
  )
}

function describe(batch: BulkImportStatusResponseDto) {
  switch (batch.status) {
    case "uploading":
      return {
        title: "You have an import in progress",
        description: "Pick up where you left off, or cancel it to start a new one.",
        badge: null,
      }
    case "queued":
      return {
        title: "Submitted, waiting for processing",
        description:
          batch.missingFilenames.length > 0
            ? `The import is queued. ${batch.missingFilenames.length} ${batch.missingFilenames.length === 1 ? "row has" : "rows have"} no file and will fail.`
            : "The import is queued and will be processed shortly. You can still cancel it until processing starts.",
        badge: (
          <Badge variant="gold">
            <Clock aria-hidden="true" />
            Queued
          </Badge>
        ),
      }
    default:
      return {
        title: "Processing your import",
        description: "Documents are being created. It can no longer be cancelled.",
        badge: (
          <Badge variant="outline">
            <Loader2 className="animate-spin" aria-hidden="true" />
            Processing
          </Badge>
        ),
      }
  }
}

export function formatDateTime(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" })
}
