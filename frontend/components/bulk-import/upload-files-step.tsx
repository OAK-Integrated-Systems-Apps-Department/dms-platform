"use client"

import { useState } from "react"
import { Loader2, Send, X } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { FileDropzone } from "@/components/ui/file-dropzone"
import type { BulkImportStatusResponseDto } from "@/lib/bulk-imports/api"
import type { UploadQueueEntry } from "@/lib/bulk-imports/use-upload-queue"

import { ConfirmActionDialog } from "./confirm-action-dialog"
import { InlineError, InlineSuccess, StepCard } from "./step-card"

interface UploadFilesStepProps {
  status: BulkImportStatusResponseDto
  /** Shown once, right after the template was accepted. */
  createdNotice: string | null
  entries: UploadQueueEntry[]
  uploading: boolean
  onFiles: (files: File[]) => void
  onSubmit: () => Promise<string | null>
  onCancel: () => Promise<string | null>
}

/** Steps 3 and 4: upload the files the template lists (queued, one at a time), then submit. */
export function UploadFilesStep({
  status,
  createdNotice,
  entries,
  uploading,
  onFiles,
  onSubmit,
  onCancel,
}: UploadFilesStepProps) {
  const [confirmSubmitOpen, setConfirmSubmitOpen] = useState(false)
  const [cancelOpen, setCancelOpen] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)

  const missingCount = status.missingFilenames.length
  const allReceived = missingCount === 0

  async function submitNow() {
    setSubmitting(true)
    setSubmitError(null)
    const error = await onSubmit()
    setSubmitting(false)
    if (error) setSubmitError(error)
  }

  function handleSubmitClick() {
    if (allReceived) {
      void submitNow()
    } else {
      setConfirmSubmitOpen(true)
    }
  }

  const cancelButton = (
    <Button variant="outline" size="sm" onClick={() => setCancelOpen(true)} disabled={submitting}>
      <X />
      Cancel import
    </Button>
  )

  return (
    <div className="flex flex-col gap-4">
      <StepCard
        step={3}
        title="Upload the files"
        description="Drop the files named in your template. They're matched to rows by filename and uploaded one at a time."
        action={cancelButton}
      >
        {createdNotice && <InlineSuccess>{createdNotice}</InlineSuccess>}

        <FileDropzone multiple onFiles={onFiles} label="Upload import files">
          <span className="font-semibold text-foreground">Drag files here</span> or click to browse
          <span className="mt-0.5 block text-[12px]">Up to 100 MB per file</span>
        </FileDropzone>

        <div className="mt-5 grid gap-5 md:grid-cols-2">
          <div>
            <div className="mb-2 flex items-baseline justify-between gap-2">
              <h3 className="text-[13px] font-semibold">Uploads</h3>
              <p className="text-[12.5px] text-muted-foreground" aria-live="polite">
                <span className="font-semibold text-foreground">{status.filesReceived}</span> of{" "}
                {status.totalRecords} received
              </p>
            </div>
            {entries.length === 0 ? (
              <p className="rounded-md border border-dashed border-border px-3.5 py-3 text-[12.5px] text-muted-foreground">
                No files added yet.
              </p>
            ) : (
              <ul className="max-h-[320px] divide-y divide-border overflow-y-auto rounded-md border border-border">
                {entries.map((entry) => (
                  <UploadRow key={entry.id} entry={entry} />
                ))}
              </ul>
            )}
          </div>

          <div>
            <h3 className="mb-2 text-[13px] font-semibold">
              Still missing {missingCount > 0 && <span className="text-muted-foreground">({missingCount})</span>}
            </h3>
            {allReceived ? (
              <p className="rounded-md border border-dashed border-border px-3.5 py-3 text-[12.5px] text-muted-foreground">
                Every row has its file.
              </p>
            ) : (
              <ul className="max-h-[320px] overflow-y-auto rounded-md border border-border px-3.5 py-2 text-[12.5px]">
                {status.missingFilenames.map((filename, index) => (
                  <li key={`${filename}-${index}`} className="truncate py-0.5 font-mono" title={filename}>
                    {filename}
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>
      </StepCard>

      <StepCard
        step={4}
        title="Submit for processing"
        description={
          allReceived
            ? "All files are in. Submit to queue the import."
            : `${missingCount} ${missingCount === 1 ? "row has" : "rows have"} no file yet. You can keep uploading, or submit now and let those rows fail.`
        }
        action={cancelButton}
      >
        <Button onClick={handleSubmitClick} disabled={uploading || submitting}>
          {submitting ? <Loader2 className="animate-spin" /> : <Send />}
          {submitting ? "Submitting…" : "Submit import"}
        </Button>
        {uploading && (
          <p className="mt-2 text-[12.5px] text-muted-foreground">Wait for the uploads in progress to finish.</p>
        )}
        {submitError && <InlineError>{submitError}</InlineError>}
      </StepCard>

      <ConfirmActionDialog
        open={confirmSubmitOpen}
        onOpenChange={setConfirmSubmitOpen}
        title="Submit with missing files?"
        description={`${missingCount} ${missingCount === 1 ? "row has" : "rows have"} no file and will fail.`}
        confirmLabel="Submit anyway"
        pendingLabel="Submitting…"
        onConfirm={onSubmit}
      />
      <CancelImportDialog open={cancelOpen} onOpenChange={setCancelOpen} onConfirm={onCancel} />
    </div>
  )
}

export function CancelImportDialog({
  open,
  onOpenChange,
  onConfirm,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  onConfirm: () => Promise<string | null>
}) {
  return (
    <ConfirmActionDialog
      open={open}
      onOpenChange={onOpenChange}
      title="Cancel this import?"
      description="Files uploaded so far will be discarded and nothing will be imported. You can start a new import afterwards."
      confirmLabel="Cancel import"
      pendingLabel="Cancelling…"
      cancelLabel="Keep import"
      onConfirm={onConfirm}
    />
  )
}

function UploadRow({ entry }: { entry: UploadQueueEntry }) {
  return (
    <li className="flex items-start justify-between gap-3 px-3.5 py-2.5">
      <div className="min-w-0">
        <p className="truncate text-[13px] font-medium" title={entry.file.name}>
          {entry.file.name}
        </p>
        {entry.state === "error" && entry.error && (
          <p className="mt-0.5 text-[12px] leading-[1.5] text-[var(--rust)]">{entry.error}</p>
        )}
      </div>
      <UploadStateBadge entry={entry} />
    </li>
  )
}

function UploadStateBadge({ entry }: { entry: UploadQueueEntry }) {
  switch (entry.state) {
    case "waiting":
      return <Badge variant="muted">Waiting</Badge>
    case "uploading":
      return (
        <Badge variant="outline">
          <Loader2 className="animate-spin" aria-hidden="true" />
          Uploading
        </Badge>
      )
    case "matched":
      return <Badge variant="default">Matched to row {entry.rowNumber}</Badge>
    case "error":
      return <Badge variant="rust">Error</Badge>
  }
}
