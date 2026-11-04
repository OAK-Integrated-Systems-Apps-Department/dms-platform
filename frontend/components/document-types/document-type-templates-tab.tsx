"use client"

import { LoadFailed } from "@/components/ui/load-failed"
import { useRef, useState } from "react"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import { Download, Eye, Loader2, Pencil, RefreshCw, Trash2 } from "lucide-react"
import { toast } from "sonner"

import { fileKind, FriendlyDate } from "@/components/documents/browser-items"
import { DocumentPreview } from "@/components/documents/document-preview"
import { Button } from "@/components/ui/button"
import { ConfirmActionDialog } from "@/components/ui/confirm-action-dialog"
import { FileDropzone } from "@/components/ui/file-dropzone"
import { Input } from "@/components/ui/input"
import { friendlyMessage } from "@/lib/api-error"
import {
  downloadDocumentFile,
  type IngestionTicketDto,
} from "@/lib/documents/api"
import {
  addTemplate,
  fetchTemplates,
  removeTemplate,
  renameTemplate,
  replaceTemplate,
  TEMPLATES_KEY,
  type DocumentTemplateDto,
} from "@/lib/documents/templates"
import { saveDownloadedFile } from "@/lib/download-file"
import { formatBytes } from "@/lib/quarantine/quarantine-badges"
import { cn } from "@/lib/utils"

/** What a template upload's ticket means for the admin. */
function ticketMessage(ticket: IngestionTicketDto, done: string) {
  return ticket.status === "pending"
    ? `${done} once its virus scan finishes.`
    : `${done}.`
}

function nameFromFile(file: File) {
  const dot = file.name.lastIndexOf(".")
  return (dot > 0 ? file.name.slice(0, dot) : file.name).trim() || file.name
}

/** For DocumentPreview, which expects a document row. */
export function templateAsRow(template: DocumentTemplateDto) {
  return {
    documentId: template.templateId,
    title: template.name,
    fileName: template.fileName,
    mimeType: template.mimeType,
    sizeBytes: template.sizeBytes,
    documentTypeName: template.documentTypeName,
    createdAt: template.updatedAt,
  }
}

export async function downloadTemplate(template: DocumentTemplateDto) {
  try {
    saveDownloadedFile(await downloadDocumentFile(template.templateId))
  } catch (error) {
    toast.error(friendlyMessage(error, "Could not download the template."))
  }
}

/**
 * A document type's templates: ready-made files (a blank contract in Word, a
 * proposal in PowerPoint, a form as PDF) that people download or start a new
 * document from in My Documents. Each is virus-scanned when added or
 * replaced; replacing keeps the old file in its version history.
 */
export function DocumentTypeTemplatesTab({
  documentTypeId,
  canCreate,
  canUpdate,
  canDelete,
}: {
  documentTypeId: string
  /** mdtm.manage-document-templates.create - add templates. */
  canCreate: boolean
  /** ...update - rename, replace the file. */
  canUpdate: boolean
  /** ...delete - remove. */
  canDelete: boolean
}) {
  const queryClient = useQueryClient()
  const key = [...TEMPLATES_KEY, documentTypeId]
  const templates = useQuery({
    queryKey: key,
    queryFn: () => fetchTemplates(documentTypeId),
  })
  const [uploading, setUploading] = useState<{
    name: string
    percent: number
  } | null>(null)
  const [preview, setPreview] = useState<DocumentTemplateDto | null>(null)
  const [renaming, setRenaming] = useState<{
    id: string
    name: string
  } | null>(null)
  const [removing, setRemoving] = useState<DocumentTemplateDto | null>(null)
  const replaceInput = useRef<HTMLInputElement>(null)
  const [replacing, setReplacing] = useState<DocumentTemplateDto | null>(null)

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: TEMPLATES_KEY })
  }

  const transfer = (name: string) => ({
    onProgress: (loaded: number, total: number) =>
      setUploading({ name, percent: Math.round((loaded / total) * 100) }),
  })

  async function add(files: File[]) {
    for (const file of files) {
      setUploading({ name: file.name, percent: 0 })
      try {
        const ticket = await addTemplate(
          documentTypeId,
          file,
          nameFromFile(file),
          transfer(file.name)
        )
        toast.success(ticketMessage(ticket, `"${nameFromFile(file)}" added`))
      } catch (error) {
        toast.error(
          `${file.name}: ${friendlyMessage(error, "could not be added.")}`
        )
      }
    }
    setUploading(null)
    refresh()
  }

  async function replace(template: DocumentTemplateDto, file: File) {
    setUploading({ name: file.name, percent: 0 })
    try {
      const ticket = await replaceTemplate(
        template.templateId,
        file,
        transfer(file.name)
      )
      toast.success(ticketMessage(ticket, `"${template.name}" replaced`))
    } catch (error) {
      toast.error(friendlyMessage(error, "Could not replace the template."))
    } finally {
      setUploading(null)
      refresh()
    }
  }

  async function saveName() {
    if (!renaming) return
    const name = renaming.name.trim()
    if (!name) return
    try {
      await renameTemplate(renaming.id, name)
      setRenaming(null)
      refresh()
    } catch (error) {
      toast.error(friendlyMessage(error, "Could not rename the template."))
    }
  }

  const rows = templates.data ?? []

  return (
    <div className="flex flex-col gap-4">
      <p className="text-[13px] leading-[1.6] text-muted-foreground">
        Ready-made files for this document type - a blank contract, a proposal,
        a form. People download them, or start a new document from one, in My
        Documents. Any file type allowed for documents can be a template - Word,
        PowerPoint, Excel, PDF and more - and each is checked for viruses first.
      </p>

      {canCreate && (
        <FileDropzone
          multiple
          disabled={uploading !== null}
          onFiles={(files) => void add(files)}
          label="Add templates"
        >
          {uploading ? (
            <span className="flex items-center gap-2 text-[13px]">
              <Loader2 className="size-4 animate-spin text-primary" />
              {uploading.percent < 100
                ? `Uploading ${uploading.name}… ${uploading.percent}%`
                : `Checking ${uploading.name} for viruses…`}
            </span>
          ) : undefined}
        </FileDropzone>
      )}

      <div className="overflow-hidden rounded-lg border border-border bg-card shadow-[var(--ledger-shadow)]">
        {templates.isPending ? (
          <p className="px-4 py-8 text-center text-[13px] text-muted-foreground">
            Loading templates…
          </p>
        ) : templates.isError ? (
          <LoadFailed error={templates.error} fallback="Could not load templates." />
        ) : rows.length === 0 ? (
          <p className="px-4 py-8 text-center text-[13px] text-muted-foreground">
            No templates yet.
            {canCreate && " Drag files above to add the first one."}
          </p>
        ) : (
          <ul className="divide-y divide-border">
            {rows.map((template) => {
              const kind = fileKind(template.fileName)
              const editing = renaming?.id === template.templateId
              return (
                <li
                  key={template.templateId}
                  className="flex items-center gap-3 px-4 py-3"
                >
                  <span
                    className={cn(
                      "flex size-9 shrink-0 items-center justify-center rounded",
                      kind.tone
                    )}
                  >
                    <kind.icon size={18} aria-hidden="true" />
                  </span>
                  <div className="min-w-0 flex-1">
                    {editing ? (
                      <form
                        className="flex items-center gap-2"
                        onSubmit={(e) => {
                          e.preventDefault()
                          void saveName()
                        }}
                      >
                        <Input
                          autoFocus
                          value={renaming.name}
                          maxLength={255}
                          aria-label="Template name"
                          onChange={(e) =>
                            setRenaming({ ...renaming, name: e.target.value })
                          }
                          className="h-8 text-[13px]"
                        />
                        <Button type="submit" size="sm">
                          Save
                        </Button>
                        <Button
                          type="button"
                          size="sm"
                          variant="ghost"
                          onClick={() => setRenaming(null)}
                        >
                          Cancel
                        </Button>
                      </form>
                    ) : (
                      <p className="truncate text-[13.5px] font-semibold">
                        {template.name}
                      </p>
                    )}
                    <p className="mt-0.5 truncate font-mono text-[11.5px] text-muted-foreground">
                      {template.fileName}
                      {template.sizeBytes !== null &&
                        ` · ${formatBytes(template.sizeBytes)}`}
                      {template.versionLabel && ` · v${template.versionLabel}`}
                      {" · "}
                      <FriendlyDate iso={template.updatedAt} />
                    </p>
                  </div>
                  {!editing && (
                    <div className="flex shrink-0 items-center gap-1">
                      <IconButton
                        label={`Preview ${template.name}`}
                        onClick={() => setPreview(template)}
                      >
                        <Eye size={15} />
                      </IconButton>
                      <IconButton
                        label={`Download ${template.name}`}
                        onClick={() => void downloadTemplate(template)}
                      >
                        <Download size={15} />
                      </IconButton>
                      {canUpdate && (
                        <>
                          <IconButton
                            label={`Replace the file of ${template.name}`}
                            disabled={uploading !== null}
                            onClick={() => {
                              setReplacing(template)
                              replaceInput.current?.click()
                            }}
                          >
                            <RefreshCw size={15} />
                          </IconButton>
                          <IconButton
                            label={`Rename ${template.name}`}
                            onClick={() =>
                              setRenaming({
                                id: template.templateId,
                                name: template.name,
                              })
                            }
                          >
                            <Pencil size={15} />
                          </IconButton>
                        </>
                      )}
                      {canDelete && (
                        <IconButton
                          label={`Remove ${template.name}`}
                          danger
                          onClick={() => setRemoving(template)}
                        >
                          <Trash2 size={15} />
                        </IconButton>
                      )}
                    </div>
                  )}
                </li>
              )
            })}
          </ul>
        )}
      </div>

      <input
        ref={replaceInput}
        type="file"
        hidden
        onChange={(e) => {
          const file = e.target.files?.[0]
          e.target.value = ""
          if (file && replacing) void replace(replacing, file)
          setReplacing(null)
        }}
      />

      <ConfirmActionDialog
        open={removing !== null}
        onOpenChange={(open) => !open && setRemoving(null)}
        title={removing ? `Remove "${removing.name}"?` : ""}
        description="It won't be offered as a template any more. Documents already made from it aren't affected."
        confirmLabel="Remove"
        pendingLabel="Removing…"
        onConfirm={async () => {
          if (!removing) return null
          try {
            await removeTemplate(removing.templateId)
            toast.success(`"${removing.name}" removed.`)
            refresh()
            return null
          } catch (error) {
            return friendlyMessage(error, "Could not remove the template.")
          }
        }}
      />

      {preview && (
        <DocumentPreview
          key={preview.templateId}
          row={templateAsRow(preview)}
          onOpenChange={(open) => !open && setPreview(null)}
          onDownload={() => void downloadTemplate(preview)}
        />
      )}
    </div>
  )
}

function IconButton({
  label,
  onClick,
  disabled,
  danger,
  children,
}: {
  label: string
  onClick: () => void
  disabled?: boolean
  danger?: boolean
  children: React.ReactNode
}) {
  return (
    <button
      type="button"
      title={label}
      aria-label={label}
      disabled={disabled}
      onClick={onClick}
      className={cn(
        "flex size-8 items-center justify-center rounded-md text-muted-foreground transition-colors disabled:opacity-40",
        danger
          ? "hover:bg-destructive/10 hover:text-destructive"
          : "hover:bg-muted hover:text-foreground"
      )}
    >
      {children}
    </button>
  )
}
