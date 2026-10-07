"use client"

import { useState } from "react"
import { Loader2, RotateCw, Send, X } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { FileDropzone } from "@/components/ui/file-dropzone"
import type { BulkImportStatusResponseDto } from "@/lib/bulk-imports/api"
import type { UploadQueueEntry } from "@/lib/bulk-imports/use-upload-queue"

import { ConfirmActionDialog } from "./confirm-action-dialog"
import { InlineError, StepCard } from "./step-card"

interface UploadFilesStepProps {
  status: BulkImportStatusResponseDto
  /** Most files (accepted and rejected) the batch can take - known only from the create response, null after a resume. */
  maxFiles: number | null
  entries: UploadQueueEntry[]
  uploading: boolean
  /** False when the caller lacks document-create: uploading and submitting are disabled, Cancel still works. */
  uploadsAllowed: boolean
  onFiles: (files: File[]) => void
  /** Re-sends an upload that got no answer from the server, with the same clientFileId. */
  onRetry: (id: string) => void
  onSubmit: () => Promise<string | null>
  onCancel: () => Promise<string | null>
}

/**
 * Steps 2 and 3: add files (queued, uploaded one at a time - each accepted
 * file becomes a document), then submit. Rejected files stay listed with
 * the reason; the server keeps them as failed items in the results.
 */
export function UploadFilesStep({
  status,
  maxFiles,
  entries,
  uploading,
  uploadsAllowed,
  onFiles,
  onRetry,
  onSubmit,
  onCancel,
}: UploadFilesStepProps) {
  const [confirmSubmitOpen, setConfirmSubmitOpen] = useState(false)
  const [cancelOpen, setCancelOpen] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)

  const { filesReceived, filesRejected } = status
  /** Uploads that got no answer and haven't been retried - the server may never have seen them. */
  const failedUploads = entries.filter((entry) => entry.state === "failed").length
  const canSubmit = uploadsAllowed && filesReceived > 0 && !uploading && !submitting

  async function submitNow() {
    setSubmitting(true)
    setSubmitError(null)
    const error = await onSubmit()
    setSubmitting(false)
    if (error) setSubmitError(error)
  }

  function handleSubmitClick() {
    if (filesRejected > 0 || failedUploads > 0) {
      setConfirmSubmitOpen(true)
    } else {
      void submitNow()
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
        step={2}
        title="Add files"
        description="Drop the files to import. Each one becomes a document; they're uploaded one at a time."
        action={cancelButton}
      >
        <FileDropzone multiple onFiles={onFiles} disabled={!uploadsAllowed} label="Upload import files">
          <span className="font-semibold text-foreground">Drag files here</span> or click to browse
          <span className="mt-0.5 block text-[12px]">
            Up to 100 MB per file{maxFiles !== null && `, ${maxFiles} files per import`}
          </span>
        </FileDropzone>

        <p className="mt-4 text-[12.5px] text-muted-foreground" aria-live="polite">
          <span className="font-semibold text-foreground">{filesReceived}</span>{" "}
          {filesReceived === 1 ? "file" : "files"} added,{" "}
          <span className="font-semibold text-foreground">{filesRejected}</span> rejected
        </p>

        <div className="mt-3 grid gap-5 md:grid-cols-2">
          <div>
            <h3 className="mb-2 text-[13px] font-semibold">Uploads</h3>
            {entries.length === 0 ? (
              <p className="rounded-md border border-dashed border-border px-3.5 py-3 text-[12.5px] text-muted-foreground">
                No files added yet.
              </p>
            ) : (
              <ul className="max-h-[320px] divide-y divide-border overflow-y-auto rounded-md border border-border">
                {entries.map((entry) => (
                  <UploadRow key={entry.id} entry={entry} onRetry={onRetry} />
                ))}
              </ul>
            )}
          </div>

          <div>
            <h3 className="mb-2 text-[13px] font-semibold">
              Rejected files {filesRejected > 0 && <span className="text-muted-foreground">({filesRejected})</span>}
            </h3>
            {filesRejected === 0 ? (
              <p className="rounded-md border border-dashed border-border px-3.5 py-3 text-[12.5px] text-muted-foreground">
                No files have been rejected.
              </p>
            ) : (
              <>
                <ul className="max-h-[320px] overflow-y-auto rounded-md border border-border px-3.5 py-2 text-[12.5px]">
                  {status.rejectedFilenames.map((filename, index) => (
                    <li key={`${filename}-${index}`} className="truncate py-0.5 font-mono" title={filename}>
                      {filename}
                    </li>
                  ))}
                </ul>
                <p className="mt-2 text-[12px] text-muted-foreground">
                  These will be listed as failed in the results.
                </p>
              </>
            )}
          </div>
        </div>
      </StepCard>

      <StepCard
        step={3}
        title="Submit for processing"
        description={
          filesReceived === 0
            ? "Add at least one file that can be imported, then submit to queue the import."
            : "Submit to queue the import. You can keep adding files until you do."
        }
        action={cancelButton}
      >
        <Button onClick={handleSubmitClick} disabled={!canSubmit}>
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
        title={submitConfirmTitle(filesRejected, failedUploads)}
        description={submitConfirmDescription(filesRejected, failedUploads)}
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

function submitConfirmTitle(rejected: number, failed: number): string {
  if (rejected > 0 && failed > 0) return "Submit with rejected and failed files?"
  if (failed > 0) return "Submit without failed uploads?"
  return "Submit with rejected files?"
}

function submitConfirmDescription(rejected: number, failed: number): string {
  const sentences: string[] = []
  if (rejected > 0) {
    sentences.push(
      `${rejected} ${rejected === 1 ? "file was" : "files were"} rejected and will be listed as failed in the results.`
    )
  }
  if (failed > 0) {
    const one = failed === 1
    sentences.push(
      `${failed} ${one ? "file didn't" : "files didn't"} finish uploading and ${one ? "hasn't" : "haven't"} been retried - ` +
        `${one ? "it" : "they"} won't be included. Retry ${one ? "it" : "them"} first, or submit without ${one ? "it" : "them"}.`
    )
  }
  return sentences.join(" ")
}

function UploadRow({ entry, onRetry }: { entry: UploadQueueEntry; onRetry: (id: string) => void }) {
  return (
    <li className="flex items-start justify-between gap-3 px-3.5 py-2.5">
      <div className="min-w-0">
        <p className="truncate text-[13px] font-medium" title={entry.file.name}>
          {entry.file.name}
        </p>
        {(entry.state === "rejected" || entry.state === "failed") && entry.error && (
          <p className="mt-0.5 text-[12px] leading-[1.5] text-[var(--rust)]">{entry.error}</p>
        )}
      </div>
      <div className="flex shrink-0 items-center gap-2">
        {entry.state === "failed" && (
          <Button variant="outline" size="xs" onClick={() => onRetry(entry.id)}>
            <RotateCw />
            Retry
          </Button>
        )}
        <UploadStateBadge entry={entry} />
      </div>
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
    case "accepted":
      return <Badge variant="default">Accepted</Badge>
    case "rejected":
      return <Badge variant="rust">Rejected</Badge>
    case "failed":
      return <Badge variant="rust">Upload failed</Badge>
  }
}
