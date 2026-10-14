"use client"

import { useState } from "react"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import {
  Download,
  Eye,
  History,
  Loader2,
  RotateCcw,
  Upload,
} from "lucide-react"
import { toast } from "sonner"

import { ConfirmActionDialog } from "@/components/ui/confirm-action-dialog"
import { FriendlyDate } from "@/components/documents/browser-items"
import { DocumentPreview } from "@/components/documents/document-preview"
import { Button } from "@/components/ui/button"
import { FileDropzone } from "@/components/ui/file-dropzone"
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet"
import { Textarea } from "@/components/ui/textarea"
import { friendlyMessage } from "@/lib/api-error"
import { saveDownloadedFile } from "@/lib/download-file"
import {
  downloadDocumentVersion,
  fetchDocumentVersions,
  restoreDocumentVersion,
  uploadDocumentVersion,
  type DocumentVersionDto,
  type IngestionTicketDto,
} from "@/lib/documents/api"
import { sha256Hex } from "@/lib/documents/duplicates"
import { formatBytes } from "@/lib/quarantine/quarantine-badges"
import { cn } from "@/lib/utils"

const REASONS: Record<string, string> = {
  initial_upload: "Uploaded",
  replacement_upload: "New version",
  rollback: "Restored",
  workflow_update: "Workflow update",
  signed_copy: "Signed copy",
  migration: "Migrated",
}

/** What a scan ticket means for a new version, in words. */
function ticketMessage(ticket: IngestionTicketDto, what: string): string {
  if (ticket.status === "clean")
    return `${what} - it's now the current version.`
  if (ticket.status === "pending")
    return `${what} - it becomes the current version once its virus scan finishes.`
  return ticket.message ?? "The file was blocked by the virus scan."
}

/**
 * A document's version history (DVC): every version, newest first, with who
 * uploaded it and what changed. Any version can be downloaded; an older one
 * can be restored (added again as a new version - nothing is rewritten), and
 * a new version uploaded. Every upload is virus-scanned before it counts.
 */
export function VersionHistorySheet({
  documentId,
  title,
  canUpload,
  canRestore,
  onOpenChange,
  onChanged,
}: {
  documentId: string
  title: string
  /** may add a version: the create permission and edit access to the document */
  canUpload: boolean
  /** may restore one: the restore permission and edit access to the document */
  canRestore: boolean
  onOpenChange: (open: boolean) => void
  /** the document's current version changed - refresh what shows it */
  onChanged: () => void
}) {
  const queryClient = useQueryClient()
  const key = ["documents", "versions", documentId]
  const versions = useQuery({
    queryKey: key,
    queryFn: () => fetchDocumentVersions(documentId),
  })
  const [file, setFile] = useState<File | null>(null)
  const [comment, setComment] = useState("")
  const [uploading, setUploading] = useState(false)
  const [restoring, setRestoring] = useState<DocumentVersionDto | null>(null)
  const [previewing, setPreviewing] = useState<DocumentVersionDto | null>(null)

  function changed() {
    void queryClient.invalidateQueries({ queryKey: key })
    onChanged()
  }

  async function upload() {
    if (!file) return
    setUploading(true)
    try {
      // The same file as a version already in the history would change nothing - say so instead.
      const hash = await sha256Hex(file)
      const same = hash
        ? versions.data?.find((v) => v.fileHash === hash)
        : undefined
      if (same) {
        toast.error(
          same.current
            ? "This file is identical to the current version - there's nothing new to upload."
            : `This file is identical to version ${same.versionLabel}. Restore that version instead.`
        )
        return
      }
      const ticket = await uploadDocumentVersion(documentId, file, comment)
      toast.success(ticketMessage(ticket, "New version uploaded"))
      setFile(null)
      setComment("")
      changed()
    } catch (error) {
      toast.error(friendlyMessage(error, "Could not upload the new version."))
    } finally {
      setUploading(false)
    }
  }

  async function download(version: DocumentVersionDto) {
    try {
      saveDownloadedFile(
        await downloadDocumentVersion(documentId, version.versionId)
      )
    } catch (error) {
      toast.error(friendlyMessage(error, "Could not download that version."))
    }
  }

  return (
    <Sheet open onOpenChange={onOpenChange}>
      <SheetContent
        side="right"
        className="w-full gap-0 data-[side=right]:sm:max-w-[560px]"
      >
        <SheetHeader className="border-b border-border">
          <SheetTitle className="flex items-center gap-2">
            <History size={18} className="text-primary" />
            Version history
          </SheetTitle>
          <SheetDescription className="truncate">{title}</SheetDescription>
        </SheetHeader>

        <div className="flex min-h-0 flex-1 flex-col gap-5 overflow-y-auto p-5">
          {canUpload && (
            <section className="flex flex-col gap-3 rounded-xl border border-border bg-muted/20 p-4">
              <h3 className="text-[13px] font-semibold">
                Upload a new version
              </h3>
              <FileDropzone
                onFiles={(files) => setFile(files[0] ?? null)}
                disabled={uploading}
                label="The new version's file"
              >
                {file ? (
                  <span className="text-[13px] font-medium">
                    {file.name}{" "}
                    <span className="font-normal text-muted-foreground">
                      ({formatBytes(file.size)})
                    </span>
                  </span>
                ) : undefined}
              </FileDropzone>
              <Textarea
                value={comment}
                onChange={(event) => setComment(event.target.value)}
                placeholder="What changed? (optional)"
                maxLength={1000}
                rows={2}
                disabled={uploading}
              />
              <div>
                <Button
                  onClick={() => void upload()}
                  disabled={!file || uploading}
                >
                  {uploading ? (
                    <Loader2 className="animate-spin" />
                  ) : (
                    <Upload />
                  )}
                  Upload version
                </Button>
              </div>
            </section>
          )}

          {versions.isPending ? (
            <p className="flex items-center gap-2 text-[13px] text-muted-foreground">
              <Loader2 size={14} className="animate-spin" /> Loading versions…
            </p>
          ) : versions.isError ? (
            <p className="text-[13px] text-[var(--rust)]">
              {friendlyMessage(
                versions.error,
                "Could not load the version history."
              )}
            </p>
          ) : (
            <ol className="flex flex-col gap-2.5">
              {versions.data.map((version) => (
                <li
                  key={version.versionId}
                  className={cn(
                    "rounded-xl border bg-card p-3.5",
                    version.current ? "border-primary/50" : "border-border"
                  )}
                >
                  <div className="flex items-start gap-3">
                    <span className="flex h-7 min-w-11 shrink-0 items-center justify-center rounded-md bg-primary/10 px-2 font-mono text-[12px] font-semibold text-primary">
                      v{version.versionLabel}
                    </span>
                    <div className="min-w-0 flex-1">
                      <p className="flex flex-wrap items-center gap-x-2 text-[13px] font-medium">
                        <span className="truncate">
                          {version.originalFilename}
                        </span>
                        {version.current && (
                          <span className="rounded-full bg-primary/10 px-2 py-px text-[11px] font-semibold text-primary">
                            Current
                          </span>
                        )}
                      </p>
                      <p className="mt-0.5 text-[12px] text-muted-foreground">
                        {REASONS[version.createdReason] ??
                          version.createdReason}
                        {" · "}
                        {version.createdByName ?? "Someone"}
                        {" · "}
                        <FriendlyDate iso={version.createdAt} />
                        {" · "}
                        {formatBytes(version.sizeBytes)}
                      </p>
                      {version.versionComment && (
                        <p className="mt-1.5 text-[12.5px] leading-[1.5]">
                          {version.versionComment}
                        </p>
                      )}
                      {!version.servable && (
                        <p className="mt-1.5 text-[12px] text-[var(--rust)]">
                          Not available - it didn&apos;t pass the virus scan.
                        </p>
                      )}
                    </div>
                    <div className="flex shrink-0 gap-1">
                      <Button
                        variant="ghost"
                        size="icon-sm"
                        aria-label={`Preview version ${version.versionLabel}`}
                        title="Preview"
                        disabled={!version.servable}
                        onClick={() => setPreviewing(version)}
                      >
                        <Eye />
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon-sm"
                        aria-label={`Download version ${version.versionLabel}`}
                        title="Download"
                        disabled={!version.servable}
                        onClick={() => void download(version)}
                      >
                        <Download />
                      </Button>
                      {canRestore && !version.current && (
                        <Button
                          variant="ghost"
                          size="icon-sm"
                          aria-label={`Restore version ${version.versionLabel}`}
                          title="Restore this version"
                          disabled={!version.servable}
                          onClick={() => setRestoring(version)}
                        >
                          <RotateCcw />
                        </Button>
                      )}
                    </div>
                  </div>
                </li>
              ))}
            </ol>
          )}
        </div>
      </SheetContent>

      {previewing && (
        <DocumentPreview
          key={previewing.versionId}
          row={{
            documentId,
            title,
            fileName: previewing.originalFilename,
            mimeType: previewing.mimeType,
            sizeBytes: previewing.sizeBytes,
            documentTypeName: null,
            createdAt: previewing.createdAt,
          }}
          version={{
            versionId: previewing.versionId,
            label: previewing.versionLabel,
            current: previewing.current,
          }}
          onOpenChange={(open) => !open && setPreviewing(null)}
          onDownload={() => void download(previewing)}
        />
      )}

      <ConfirmActionDialog
        open={restoring !== null}
        onOpenChange={(open) => !open && setRestoring(null)}
        title={restoring ? `Restore version ${restoring.versionLabel}?` : ""}
        description="Its content is added as a new version and becomes the current one. Nothing in the history is changed or lost."
        confirmLabel="Restore"
        pendingLabel="Restoring…"
        onConfirm={async () => {
          if (!restoring) return null
          try {
            const ticket = await restoreDocumentVersion(
              documentId,
              restoring.versionId
            )
            toast.success(
              ticketMessage(
                ticket,
                `Version ${restoring.versionLabel} restored`
              )
            )
            changed()
            return null
          } catch (error) {
            return friendlyMessage(error, "Could not restore that version.")
          }
        }}
      />
    </Sheet>
  )
}
