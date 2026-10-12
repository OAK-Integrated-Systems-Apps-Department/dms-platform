"use client"

import { useState } from "react"
import { Download } from "lucide-react"
import { toast } from "sonner"

import { InlineError } from "@/components/bulk-import/step-card"
import { Button } from "@/components/ui/button"
import { SegmentedControl } from "@/components/ui/segmented-control"
import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogDescription,
  AlertDialogPopup,
  AlertDialogPortal,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import {
  exportErrorMessage,
  type BulkExportPreviewDto,
  type BulkExportSelectionDto,
  type MetadataFormat,
} from "@/lib/bulk-exports/api"
import { useCreateExport, usePreviewExport } from "@/lib/bulk-exports/use-bulk-exports"
import { formatBytes } from "@/lib/quarantine/quarantine-badges"
import { cn } from "@/lib/utils"

const FORMAT_OPTIONS: { value: MetadataFormat; label: string }[] = [
  { value: "xlsx", label: "Excel" },
  { value: "csv", label: "CSV" },
]

interface ExportActionBarProps {
  selectedCount: number
  /** Null while nothing is selected. */
  selection: BulkExportSelectionDto | null
  /** Called once the export has been requested. */
  onRequested: () => void
}

function documents(count: number): string {
  return `${count.toLocaleString()} document${count === 1 ? "" : "s"}`
}

/**
 * "N selected", the metadata format and Export. Export previews the
 * selection first: over the limits it explains which one, otherwise it
 * confirms the count and size before requesting the export.
 */
export function ExportActionBar({ selectedCount, selection, onRequested }: ExportActionBarProps) {
  const [format, setFormat] = useState<MetadataFormat>("xlsx")
  const [preview, setPreview] = useState<BulkExportPreviewDto | null>(null)
  const [previewError, setPreviewError] = useState<string | null>(null)
  const previewMutation = usePreviewExport()
  const createMutation = useCreateExport()

  async function handleExport() {
    if (!selection) return
    setPreviewError(null)
    try {
      setPreview(await previewMutation.mutateAsync(selection))
    } catch (error) {
      setPreviewError(exportErrorMessage(error))
    }
  }

  async function handleConfirm(): Promise<string | null> {
    if (!selection) return null
    try {
      await createMutation.mutateAsync({ selection, format })
    } catch (error) {
      return exportErrorMessage(error)
    }
    toast.success("Export requested. It will appear under My exports when it's ready.")
    onRequested()
    return null
  }

  const overLimits = preview !== null && !preview.withinLimits

  return (
    <>
      <div className="mt-4 flex flex-wrap items-center justify-between gap-3 rounded-lg border border-border bg-card px-[22px] py-3.5 shadow-[var(--ledger-shadow)]">
        <span className="text-[13.5px] font-semibold text-foreground" aria-live="polite">
          {selectedCount.toLocaleString()} selected
        </span>
        <div className="flex flex-wrap items-center gap-3">
          <span className="text-[12.5px] text-muted-foreground">Metadata format</span>
          <SegmentedControl value={format} onChange={setFormat} options={FORMAT_OPTIONS} />
          <Button onClick={handleExport} disabled={!selection || previewMutation.isPending} className="h-10 px-4">
            <Download aria-hidden="true" />
            {previewMutation.isPending ? "Checking…" : "Export"}
          </Button>
        </div>
      </div>
      {previewError && <InlineError>{previewError}</InlineError>}

      {preview && overLimits && (
        <LimitsDialog preview={preview} onClose={() => setPreview(null)} />
      )}

      <ExportConfirmDialog
        open={preview !== null && !overLimits}
        onOpenChange={(open) => {
          if (!open) setPreview(null)
        }}
        title={preview ? `Export ${documents(preview.documentCount)} (${formatBytes(preview.totalBytes)})?` : "Export"}
        description={`The files and a ${format === "xlsx" ? "Excel" : "CSV"} metadata sheet are zipped in the background. You can download the ZIP from My exports when it's ready.`}
        onConfirm={handleConfirm}
      />
    </>
  )
}

function LimitsDialog({ preview, onClose }: { preview: BulkExportPreviewDto; onClose: () => void }) {
  const tooMany = preview.documentCount > preview.maxDocuments
  const tooLarge = preview.totalBytes > preview.maxBytes
  const nothing = preview.documentCount === 0

  return (
    <AlertDialog open onOpenChange={(open) => !open && onClose()}>
      <AlertDialogPortal>
        <AlertDialogPopup className="max-w-[460px]">
          <AlertDialogTitle>{nothing ? "Nothing to export" : "This selection is over the export limits"}</AlertDialogTitle>
          <AlertDialogDescription>
            {nothing
              ? "None of the selected documents can be exported. Change the selection and try again."
              : "Narrow the selection - for example with a date range or a document type - or split it into several exports."}
          </AlertDialogDescription>
          {!nothing && (
            <dl className="mb-5 grid grid-cols-[auto_1fr] gap-x-6 gap-y-2 rounded-btn border border-border px-4 py-3 text-[13px]">
              <dt className="text-muted-foreground">Documents</dt>
              <dd className={cn("text-right font-semibold", tooMany && "text-[var(--rust)]")}>
                {preview.documentCount.toLocaleString()} of max {preview.maxDocuments.toLocaleString()}
              </dd>
              <dt className="text-muted-foreground">Total size</dt>
              <dd className={cn("text-right font-semibold", tooLarge && "text-[var(--rust)]")}>
                {formatBytes(preview.totalBytes)} of max {formatBytes(preview.maxBytes)}
              </dd>
            </dl>
          )}
          <div className="flex justify-center">
            <AlertDialogClose className="rounded-md border border-[var(--border-strong)] bg-card px-5 py-2.5 text-[13px] font-semibold text-foreground">
              OK
            </AlertDialogClose>
          </div>
        </AlertDialogPopup>
      </AlertDialogPortal>
    </AlertDialog>
  )
}

interface ExportConfirmDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description: string
  /** Requests the export and resolves with an error message, or null on success. */
  onConfirm: () => Promise<string | null>
}

/**
 * "Export N documents?" - behaves like bulk import's `ConfirmActionDialog`
 * (error shown inline, closes only once the request succeeds) but styled as
 * an ordinary action, not a destructive one.
 */
function ExportConfirmDialog({ open, onOpenChange, title, description, onConfirm }: ExportConfirmDialogProps) {
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function handleConfirm() {
    setSubmitting(true)
    const message = await onConfirm()
    setSubmitting(false)
    if (message) {
      setError(message)
      return
    }
    onOpenChange(false)
  }

  return (
    <AlertDialog
      open={open}
      onOpenChange={(next) => {
        if (submitting) return
        setError(null)
        onOpenChange(next)
      }}
    >
      <AlertDialogPortal>
        <AlertDialogPopup className="max-w-[440px]">
          <AlertDialogTitle>{title}</AlertDialogTitle>
          <AlertDialogDescription>{description}</AlertDialogDescription>

          {error && (
            <p
              role="alert"
              className="mb-4 rounded-btn border border-[var(--rust)] bg-[var(--rust-tint)] px-3.5 py-2.5 text-center text-[12.5px] text-[var(--rust)]"
            >
              {error}
            </p>
          )}

          <div className="flex items-center justify-center gap-2.5">
            <AlertDialogClose
              disabled={submitting}
              className="rounded-md border border-[var(--border-strong)] bg-card px-5 py-2.5 text-[13px] font-semibold text-foreground disabled:opacity-60"
            >
              Go back
            </AlertDialogClose>
            <Button onClick={handleConfirm} disabled={submitting} className="h-10 px-5">
              <Download aria-hidden="true" />
              {submitting ? "Starting…" : "Export"}
            </Button>
          </div>
        </AlertDialogPopup>
      </AlertDialogPortal>
    </AlertDialog>
  )
}
