"use client"

import { useState } from "react"
import { useRouter } from "next/navigation"
import { useQuery } from "@tanstack/react-query"
import { Download, Eye, FilePlus2, Loader2 } from "lucide-react"
import { toast } from "sonner"

import {
  downloadTemplate,
  templateAsRow,
} from "@/components/document-types/document-type-templates-tab"
import { fileKind } from "@/components/documents/browser-items"
import { DocumentPreview } from "@/components/documents/document-preview"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { SearchInput } from "@/components/ui/search-input"
import { SearchableSelect } from "@/components/ui/searchable-select"
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet"
import { friendlyMessage } from "@/lib/api-error"
import { usePermissions } from "@/lib/auth/use-permissions"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { useEditingConfig } from "@/lib/checkout/use-checkout"
import { fetchIngestionTicket, type IngestionTicketDto } from "@/lib/documents/api"
import {
  copyTemplate,
  fetchTemplates,
  TEMPLATES_KEY,
  type DocumentTemplateDto,
} from "@/lib/documents/templates"
import { formatBytes } from "@/lib/quarantine/quarantine-badges"
import { cn } from "@/lib/utils"

const SCAN_POLL_MS = 1_500
const SCAN_GIVE_UP_MS = 120_000

function fileExtension(fileName: string | null): string | undefined {
  return fileName?.includes(".") ? fileName.split(".").pop()?.toLowerCase() : undefined
}

/**
 * Tells the user how their new document got on - after its virus scan, which is what makes it appear. A clean copy
 * says so with an "Edit now" button (or "Open" when it can't be edited in the browser); a blocked one says why; one
 * still scanning after two minutes is left to appear on its own.
 */
async function announceCreated(
  ticket: IngestionTicketDto,
  name: string,
  folderName: string,
  opts: {
    fileName: string | null
    canEditOnline: boolean
    open: (documentId: string, edit: boolean) => void
    refresh: () => void
  }
) {
  let current = ticket
  const id = current.status === "pending" ? toast.loading(`Creating "${name}" - checking it for viruses…`) : undefined
  const started = Date.now()
  while (current.status === "pending" && Date.now() - started < SCAN_GIVE_UP_MS) {
    await new Promise((resolve) => setTimeout(resolve, SCAN_POLL_MS))
    try {
      current = await fetchIngestionTicket(current.scanResultId)
    } catch {
      // Not reachable just now; the scan carries on server-side - ask again.
    }
  }

  if (current.status === "pending") {
    toast.success(`"${name}" will appear in ${folderName} once its virus scan finishes.`, { id })
  } else if ((current.status === "clean" || current.status === "released_override") && current.documentId) {
    const documentId = current.documentId
    opts.refresh()
    toast.success(`Created "${name}" in ${folderName}.`, {
      id,
      description: opts.canEditOnline
        ? "Edit it online and press Ctrl+S to save your changes."
        : "Open it to download and fill it in, then check it in.",
      duration: 15_000,
      action: opts.canEditOnline
        ? { label: "Edit now", onClick: () => opts.open(documentId, true) }
        : { label: "Open", onClick: () => opts.open(documentId, false) },
    })
  } else {
    toast.error(current.message ?? `"${name}" was blocked by the virus scan.`, { id })
  }
}

/**
 * "New from template", Google Drive style: every document type's templates,
 * to download and fill in - or to start a new document from right here in
 * the open folder (a copy of the template, virus-scanned like any upload).
 */
export function TemplatePickerSheet({
  open,
  onOpenChange,
  folderId,
  folderName,
  canCreate,
  onCreated,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Where "Use" creates the document; null = the top level. */
  folderId: string | null
  folderName: string
  /** May create documents here - otherwise templates can only be downloaded. */
  canCreate: boolean
  onCreated: () => void
}) {
  const templates = useQuery({
    queryKey: [...TEMPLATES_KEY, "all"],
    queryFn: () => fetchTemplates(),
    enabled: open,
  })
  const router = useRouter()
  const { hasPermission } = usePermissions()
  const { data: editing } = useEditingConfig()
  const canEditOnline = hasPermission(PermissionCodes.CICO_CHECK_OUT) && Boolean(editing?.enabled)
  const [query, setQuery] = useState("")
  /** "" = every document type. */
  const [typeFilter, setTypeFilter] = useState("")
  const [using, setUsing] = useState<{ id: string; title: string } | null>(null)
  const [creating, setCreating] = useState(false)
  const [preview, setPreview] = useState<DocumentTemplateDto | null>(null)

  const usable = (templates.data ?? []).filter((t) => t.active)
  // Only the types that have templates, with how many each has.
  const typeOptions = [
    ...usable
      .reduce(
        (types, t) =>
          types.set(t.documentTypeId, {
            name: t.documentTypeName,
            count: (types.get(t.documentTypeId)?.count ?? 0) + 1,
          }),
        new Map<string, { name: string; count: number }>()
      )
      .entries(),
  ]
    .sort(([, a], [, b]) => a.name.localeCompare(b.name))
    .map(([value, { name, count }]) => ({
      value,
      label: `${name} (${count})`,
    }))
  const q = query.trim().toLowerCase()
  const shown = usable.filter(
    (t) =>
      (!typeFilter || t.documentTypeId === typeFilter) &&
      (!q ||
        t.name.toLowerCase().includes(q) ||
        t.documentTypeName.toLowerCase().includes(q) ||
        (t.fileName ?? "").toLowerCase().includes(q))
  )
  const groups = new Map<string, DocumentTemplateDto[]>()
  for (const t of shown)
    groups.set(t.documentTypeName, [
      ...(groups.get(t.documentTypeName) ?? []),
      t,
    ])

  async function create(template: DocumentTemplateDto, title: string) {
    setCreating(true)
    const name = title.trim() || template.name
    try {
      const ticket = await copyTemplate(template.templateId, folderId, name)
      setUsing(null)
      onCreated()
      onOpenChange(false)
      // Not a download-and-upload job any more: when the copy is ready, offer to start editing it right here.
      void announceCreated(ticket, name, folderName, {
        fileName: template.fileName,
        canEditOnline: Boolean(
          canEditOnline && fileExtension(template.fileName) && editing?.extensions.includes(fileExtension(template.fileName) as string)
        ),
        open: (documentId, edit) => router.push(edit ? `/documents/${documentId}/edit` : `/documents/${documentId}`),
        refresh: onCreated,
      })
    } catch (error) {
      toast.error(friendlyMessage(error, "Could not create the document."))
    } finally {
      setCreating(false)
    }
  }

  return (
    <Sheet open={open} onOpenChange={(next) => !creating && onOpenChange(next)}>
      <SheetContent
        side="right"
        className="w-full gap-0 data-[side=right]:sm:max-w-[560px]"
      >
        <SheetHeader className="border-b border-border">
          <SheetTitle>New from template</SheetTitle>
          <SheetDescription>
            Ready-made files for each document type. Start a new document from one in {folderName} and edit it online,
            or download one to fill in.
          </SheetDescription>
        </SheetHeader>

        <div className="flex flex-1 flex-col gap-4 overflow-y-auto p-4">
          <div className="flex flex-wrap gap-2">
            <SearchInput
              value={query}
              onChange={setQuery}
              placeholder="Search templates…"
              aria-label="Search templates"
              className="min-w-[180px] flex-1"
            />
            {typeOptions.length > 1 && (
              <SearchableSelect
                allLabel="All document types"
                searchPlaceholder="Find a document type…"
                selected={typeFilter}
                onChange={setTypeFilter}
                options={typeOptions}
                className="w-[240px] max-w-full"
              />
            )}
          </div>
          {templates.isPending ? (
            <p className="py-8 text-center text-[13px] text-muted-foreground">
              Loading templates…
            </p>
          ) : templates.isError ? (
            <p className="py-8 text-center text-[13px] text-[var(--rust)]">
              {friendlyMessage(templates.error, "Could not load templates.")}
            </p>
          ) : groups.size === 0 ? (
            <p className="rounded-lg border border-dashed border-[var(--border-strong)] px-6 py-10 text-center text-[13px] text-muted-foreground">
              {q || typeFilter
                ? "No template matches your search."
                : "No templates yet. Administrators add them to a document type, under Document Types › Templates."}
            </p>
          ) : (
            [...groups.entries()]
              .sort(([a], [b]) => a.localeCompare(b))
              .map(([typeName, list]) => (
                <section key={typeName}>
                  <h3 className="mb-1.5 text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
                    {typeName}
                  </h3>
                  <ul className="divide-y divide-border rounded-lg border border-border">
                    {list.map((template) => {
                      const kind = fileKind(template.fileName)
                      const isUsing = using?.id === template.templateId
                      return (
                        <li key={template.templateId} className="p-3">
                          <div className="flex items-center gap-3">
                            <span
                              className={cn(
                                "flex size-9 shrink-0 items-center justify-center rounded",
                                kind.tone
                              )}
                            >
                              <kind.icon size={18} aria-hidden="true" />
                            </span>
                            <div className="min-w-0 flex-1">
                              <p className="truncate text-[13.5px] font-semibold">
                                {template.name}
                              </p>
                              <p className="truncate font-mono text-[11.5px] text-muted-foreground">
                                {template.fileName}
                                {template.sizeBytes !== null &&
                                  ` · ${formatBytes(template.sizeBytes)}`}
                              </p>
                            </div>
                            <button
                              type="button"
                              title="Preview"
                              aria-label={`Preview ${template.name}`}
                              onClick={() => setPreview(template)}
                              className="flex size-8 items-center justify-center rounded-md text-muted-foreground hover:bg-muted hover:text-foreground"
                            >
                              <Eye size={15} />
                            </button>
                            <button
                              type="button"
                              title="Download"
                              aria-label={`Download ${template.name}`}
                              onClick={() => void downloadTemplate(template)}
                              className="flex size-8 items-center justify-center rounded-md text-muted-foreground hover:bg-muted hover:text-foreground"
                            >
                              <Download size={15} />
                            </button>
                            {canCreate && !isUsing && (
                              <Button
                                size="sm"
                                variant="outline"
                                onClick={() =>
                                  setUsing({
                                    id: template.templateId,
                                    title: template.name,
                                  })
                                }
                              >
                                <FilePlus2 />
                                Use
                              </Button>
                            )}
                          </div>
                          {isUsing && (
                            <form
                              className="mt-2.5 flex items-center gap-2 pl-12"
                              onSubmit={(e) => {
                                e.preventDefault()
                                void create(template, using.title)
                              }}
                            >
                              <Input
                                autoFocus
                                value={using.title}
                                maxLength={500}
                                aria-label="Title of the new document"
                                onChange={(e) =>
                                  setUsing({ ...using, title: e.target.value })
                                }
                                className="h-8 text-[13px]"
                              />
                              <Button
                                type="submit"
                                size="sm"
                                disabled={creating}
                              >
                                {creating && (
                                  <Loader2 className="animate-spin" />
                                )}
                                Create
                              </Button>
                              <Button
                                type="button"
                                size="sm"
                                variant="ghost"
                                disabled={creating}
                                onClick={() => setUsing(null)}
                              >
                                Cancel
                              </Button>
                            </form>
                          )}
                        </li>
                      )
                    })}
                  </ul>
                </section>
              ))
          )}
        </div>
      </SheetContent>

      {preview && (
        <DocumentPreview
          key={preview.templateId}
          row={templateAsRow(preview)}
          onOpenChange={(next) => !next && setPreview(null)}
          onDownload={() => void downloadTemplate(preview)}
        />
      )}
    </Sheet>
  )
}
