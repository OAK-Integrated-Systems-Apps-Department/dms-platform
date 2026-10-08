"use client"

import { useState } from "react"
import { FileUp, ListChecks, Loader2, RotateCcw, Send, X } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { FileDropzone } from "@/components/ui/file-dropzone"
import type { BulkImportStatusResponseDto } from "@/lib/bulk-imports/api"
import type { ArchiveUploadState } from "@/lib/bulk-imports/use-archive-upload"
import type { UploadQueueEntry } from "@/lib/bulk-imports/use-upload-queue"

import { ConfirmActionDialog } from "./confirm-action-dialog"
import { RowCheckPanel } from "./row-check-panel"
import { InlineError, InlineSuccess, StepCard } from "./step-card"

interface UploadFilesStepProps {
  status: BulkImportStatusResponseDto
  /** Shown once, right after the template was accepted. */
  createdNotice: string | null
  entries: UploadQueueEntry[]
  uploading: boolean
  onFiles: (files: File[]) => void
  /** The ZIP alternative to uploading files one by one. */
  archive: ArchiveUploadState
  archiveBusy: boolean
  onArchive: (file: File) => void
  onResumeArchive: () => void
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
  archive,
  archiveBusy,
  onArchive,
  onResumeArchive,
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
    <Button
      variant="outline"
      size="sm"
      onClick={() => setCancelOpen(true)}
      disabled={submitting}
    >
      <X />
      Cancel import
    </Button>
  )

  return (
    <div className="flex flex-col gap-4">
      <StepCard
        icon={FileUp}
        title="Upload the files"
        description="Drop the files named in your template - one by one or as a ZIP. They're matched to rows by filename, and each one is virus-scanned as it arrives: an infected file is refused straight away and never stored."
        action={cancelButton}
      >
        {createdNotice && <InlineSuccess>{createdNotice}</InlineSuccess>}

        <div className="grid gap-3 md:grid-cols-2">
          <FileDropzone
            multiple
            onFiles={onFiles}
            disabled={archiveBusy}
            label="Upload import files"
          >
            <span className="font-semibold text-foreground">
              Drag files here
            </span>{" "}
            or click to browse
            <span className="mt-0.5 block text-[12px]">
              Up to 100 MB per file - PDF, Office and OpenDocument files, images
              (incl. HEIC), email (.msg/.eml), Visio, DWG, text/CSV/XML/JSON
            </span>
          </FileDropzone>
          <FileDropzone
            accept=".zip"
            disabled={archiveBusy || uploading}
            onFiles={(files) => files[0] && onArchive(files[0])}
            label="Upload the files as one ZIP"
          >
            <span className="font-semibold text-foreground">
              Or drop one .zip
            </span>{" "}
            with all the files
            <span className="mt-0.5 block text-[12px]">
              Up to 2 GB - large ZIPs resume if the connection drops
            </span>
          </FileDropzone>
        </div>
        <ArchiveStatus state={archive} onResume={onResumeArchive} />

        <div className="mt-5 grid gap-5 md:grid-cols-2">
          <div>
            <div className="mb-2 flex items-baseline justify-between gap-2">
              <h3 className="text-[13px] font-semibold">Uploads</h3>
              <p
                className="text-[12.5px] text-muted-foreground"
                aria-live="polite"
              >
                <span className="font-semibold text-foreground">
                  {status.filesReceived}
                </span>{" "}
                of {status.totalRecords} received
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
              Still missing{" "}
              {missingCount > 0 && (
                <span className="text-muted-foreground">({missingCount})</span>
              )}
            </h3>
            {allReceived ? (
              <p className="rounded-md border border-dashed border-border px-3.5 py-3 text-[12.5px] text-muted-foreground">
                Every row has its file.
              </p>
            ) : (
              <ul className="max-h-[320px] overflow-y-auto rounded-md border border-border px-3.5 py-2 text-[12.5px]">
                {status.missingFilenames.map((filename, index) => (
                  <li
                    key={`${filename}-${index}`}
                    className="truncate py-0.5 font-mono"
                    title={filename}
                  >
                    {filename}
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>
      </StepCard>

      <StepCard
        icon={ListChecks}
        title="Check the rows"
        description="The template's values checked against the document type's fields now, so problems can be fixed before anything is imported."
      >
        <RowCheckPanel
          batchId={status.batchId}
          totalRecords={status.totalRecords}
        />
      </StepCard>

      <StepCard
        icon={Send}
        title="Submit for processing"
        description={
          allReceived
            ? "All files are in. Submit to queue the import."
            : `${missingCount} ${missingCount === 1 ? "row has" : "rows have"} no file yet. You can keep uploading, or submit now and let those rows fail.`
        }
        action={cancelButton}
      >
        <Button
          onClick={handleSubmitClick}
          disabled={uploading || archiveBusy || submitting}
        >
          {submitting ? <Loader2 className="animate-spin" /> : <Send />}
          {submitting ? "Submitting…" : "Submit import"}
        </Button>
        {(uploading || archiveBusy) && (
          <p className="mt-2 text-[12.5px] text-muted-foreground">
            Wait for the uploads in progress to finish.
          </p>
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
      <CancelImportDialog
        open={cancelOpen}
        onOpenChange={setCancelOpen}
        onConfirm={onCancel}
      />
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
          <p className="mt-0.5 text-[12px] leading-[1.5] text-[var(--rust)]">
            {entry.error}
          </p>
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

function ArchiveStatus({
  state,
  onResume,
}: {
  state: ArchiveUploadState
  onResume: () => void
}) {
  switch (state.kind) {
    case "idle":
      return null
    case "sending":
      return (
        <div
          className="mt-3 rounded-md border border-border px-3.5 py-2.5 text-[12.5px]"
          aria-live="polite"
        >
          <p className="mb-1.5 flex items-center gap-2">
            <Loader2 size={13} className="animate-spin" aria-hidden="true" />
            Uploading {state.fileName}
            {state.progress !== null &&
              ` - ${Math.floor(state.progress * 100)}%`}
          </p>
          {state.progress !== null && (
            <div className="h-1.5 overflow-hidden rounded-full bg-muted">
              <div
                className="h-full bg-primary transition-[width]"
                style={{ width: `${state.progress * 100}%` }}
              />
            </div>
          )}
        </div>
      )
    case "unpacking":
      return (
        <p
          className="mt-3 inline-flex items-center gap-2 text-[12.5px] text-muted-foreground"
          aria-live="polite"
        >
          <Loader2 size={13} className="animate-spin" aria-hidden="true" />
          Unpacking {state.fileName} and matching its files to rows…
        </p>
      )
    case "done": {
      const { result } = state
      return (
        <div className="mt-3">
          <InlineSuccess>
            {state.fileName}: {result.filesMatched} of {result.entriesRead}{" "}
            {result.entriesRead === 1 ? "file" : "files"} matched to rows.
          </InlineSuccess>
          {result.problemCount > 0 && (
            <ul className="mt-2 max-h-[180px] overflow-y-auto rounded-md border border-border px-3.5 py-2 text-[12px]">
              {result.problems.map((problem, i) => (
                <li key={`${problem.entry}-${i}`} className="py-0.5">
                  <span className="font-mono break-all">{problem.entry}</span>
                  <span className="text-muted-foreground">
                    {" "}
                    - {problem.message}
                  </span>
                </li>
              ))}
              {result.problemCount > result.problems.length && (
                <li className="py-0.5 text-muted-foreground">
                  …and {result.problemCount - result.problems.length} more not
                  listed.
                </li>
              )}
            </ul>
          )}
        </div>
      )
    }
    case "error":
      return (
        <div className="mt-3">
          <InlineError>
            <strong className="font-semibold">{state.fileName}: </strong>
            {state.message}
          </InlineError>
          {state.resumable && (
            <Button
              variant="outline"
              size="sm"
              className="mt-2"
              onClick={onResume}
            >
              <RotateCcw />
              Resume upload
            </Button>
          )}
        </div>
      )
  }
}
