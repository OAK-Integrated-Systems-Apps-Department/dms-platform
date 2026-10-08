"use client"

import { useState } from "react"
import { Clock, Loader2, Upload, X } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import type { BulkImportStatusResponseDto } from "@/lib/bulk-imports/api"

import { FriendlyDate } from "@/components/documents/browser-items"

import { ProgressBar, StatTile, StepCard } from "./step-card"
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
export function BatchStatusCard({
  batch,
  documentTypeName,
  onContinue,
  onCancel,
}: BatchStatusCardProps) {
  const [cancelOpen, setCancelOpen] = useState(false)
  const canCancel = batch.status === "uploading" || batch.status === "queued"

  const { title, description, badge, icon } = describe(batch)

  return (
    <StepCard icon={icon} title={title} description={description}>
      <dl className="mb-4 grid gap-3 sm:grid-cols-3">
        <StatTile label="Document type">{documentTypeName ?? "—"}</StatTile>
        <StatTile label="Started">
          <span title={formatDateTime(batch.createdAt)}>
            <FriendlyDate iso={batch.createdAt} />
          </span>
        </StatTile>
        <StatTile label="Files">
          {batch.filesReceived} of {batch.totalRecords} received
          {badge}
        </StatTile>
      </dl>
      <div className="mb-5">
        {batch.status === "running" ? (
          <ProgressBar
            value={batch.successCount + batch.failureCount}
            max={batch.totalRecords}
            label="Rows processed"
          />
        ) : (
          <ProgressBar
            value={batch.filesReceived}
            max={batch.totalRecords}
            label="Files received"
            tone={batch.status === "queued" ? "gold" : "primary"}
          />
        )}
      </div>

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

      <CancelImportDialog
        open={cancelOpen}
        onOpenChange={setCancelOpen}
        onConfirm={onCancel}
      />
    </StepCard>
  )
}

function describe(batch: BulkImportStatusResponseDto) {
  switch (batch.status) {
    case "uploading":
      return {
        title: "You have an import in progress",
        description:
          "Pick up where you left off, or cancel it to start a new one.",
        badge: null,
        icon: Upload,
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
        icon: Clock,
      }
    default:
      return {
        title: "Processing your import",
        description:
          "Documents are being created. It can no longer be cancelled.",
        badge: (
          <Badge variant="outline">
            <Loader2 className="animate-spin" aria-hidden="true" />
            Processing
          </Badge>
        ),
        icon: Loader2,
      }
  }
}

function formatDateTime(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleString(undefined, {
        dateStyle: "medium",
        timeStyle: "short",
      })
}
