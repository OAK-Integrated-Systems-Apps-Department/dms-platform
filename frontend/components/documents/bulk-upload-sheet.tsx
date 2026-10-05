"use client"

import { useRef, useState } from "react"
import { Copy, Loader2, RotateCcw, Upload, X } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { FolderSelect } from "@/components/folders/folder-select"
import { SearchableSelect } from "@/components/ui/searchable-select"
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet"
import { ApiError } from "@/lib/api-error"
import { uploadDocument, uploadDocumentVersion } from "@/lib/documents/api"
import {
  canCheckSimilar,
  checkDuplicates,
  findSimilarDocuments,
  matchReason,
  sha256Hex,
  suggestedChoice,
  type DuplicateChoice,
  type DuplicateMatchDto,
} from "@/lib/documents/duplicates"
import { cn } from "@/lib/utils"
import { useSelectableDocumentTypes } from "@/lib/documents/use-selectable-document-types"
import { formatBytes } from "@/lib/quarantine/quarantine-badges"

/** Files uploaded at the same time - enough to be quick, few enough not to flood the scan gate. */
const CONCURRENCY = 3

type ItemState =
  | { kind: "queued" }
  | { kind: "uploading" }
  | { kind: "uploaded" }
  | { kind: "versioned" }
  | { kind: "skipped" }
  | { kind: "scanning" }
  | { kind: "blocked"; message: string }
  | { kind: "rejected"; message: string }
  | { kind: "failed"; message: string }

interface UploadItem {
  id: string
  file: File
  title: string
  state: ItemState
  /** Checked against what's already in the DMS for the chosen folder. */
  checked: boolean
  /** Why it is waiting again - e.g. the same file arrived meanwhile. */
  notice?: string
  /** Already there - and what to do about it. */
  duplicate?: { match: DuplicateMatchDto; choice: DuplicateChoice }
}

const STATE_LABEL: Record<ItemState["kind"], string> = {
  queued: "Ready",
  uploading: "Uploading…",
  uploaded: "Uploaded",
  versioned: "Added as new version",
  skipped: "Skipped - already there",
  scanning: "Being scanned",
  blocked: "Blocked by malware scan",
  rejected: "Not accepted",
  failed: "Failed",
}

const STATE_VARIANT: Record<
  ItemState["kind"],
  "default" | "muted" | "gold" | "rust" | "outline"
> = {
  queued: "outline",
  uploading: "muted",
  uploaded: "default",
  versioned: "default",
  skipped: "muted",
  scanning: "gold",
  blocked: "rust",
  rejected: "rust",
  failed: "rust",
}

let nextItemId = 0

function toItems(files: File[]): UploadItem[] {
  return files.map((file) => ({
    id: `upload-${++nextItemId}`,
    file,
    title: titleFromFilename(file.name),
    state: { kind: "queued" },
    checked: false,
  }))
}

function titleFromFilename(name: string): string {
  const dot = name.lastIndexOf(".")
  return (dot > 0 ? name.slice(0, dot) : name).trim() || name
}

/** Maps one upload outcome to what the user sees - see DocumentController#uploadDocument for the codes. */
function outcomeOf(error: unknown): ItemState {
  if (error instanceof ApiError) {
    if (error.status === 422) return { kind: "blocked", message: error.message }
    if (error.status === 413)
      return {
        kind: "rejected",
        message: "File is larger than the upload limit.",
      }
    if (error.status === 415)
      return { kind: "rejected", message: "This file type is not allowed." }
    if (error.status === 403)
      return {
        kind: "rejected",
        message: "You don't have permission to upload documents.",
      }
    if (error.status === 0)
      return { kind: "failed", message: "Could not reach the server." }
    return { kind: "failed", message: error.message }
  }
  return { kind: "failed", message: "Upload failed." }
}

interface BulkUploadSheetProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /**
   * Files picked before the panel opened (e.g. from the dashboard's Upload
   * button). Read once on mount - give the sheet a new `key` each time it
   * opens so it starts fresh.
   */
  initialFiles?: File[]
  /** Folder preselected (e.g. the one being browsed); "" or absent = the top level of My Documents. Read once on mount. */
  initialFolderId?: string
  /** Called after each upload batch finishes, e.g. to refresh a document list. */
  onUploaded?: () => void
}

/**
 * Upload many files at once, all as one document type (bulk upload without
 * a ZIP). Each file goes through the normal, malware-scanned
 * `POST /api/v1/documents` on its own, CONCURRENCY at a time, and reports
 * its own result - one bad file never stops the others. Per-file metadata
 * and template-based import are the separate Bulk Import feature (Module 2.1).
 */
export function BulkUploadSheet({
  open,
  onOpenChange,
  initialFiles,
  initialFolderId,
  onUploaded,
}: BulkUploadSheetProps) {
  const [documentTypeId, setDocumentTypeId] = useState("")
  const [folderId, setFolderId] = useState(initialFolderId ?? "")
  const [items, setItems] = useState<UploadItem[]>(() =>
    toItems(initialFiles ?? [])
  )
  const [running, setRunning] = useState(false)
  const [checking, setChecking] = useState(false)
  const [dragOver, setDragOver] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const types = useSelectableDocumentTypes(open)

  function addFiles(files: File[]) {
    setItems((current) => [...current, ...toItems(files)])
  }

  function update(id: string, patch: Partial<UploadItem>) {
    setItems((current) =>
      current.map((item) => (item.id === id ? { ...item, ...patch } : item))
    )
  }

  /** A different folder changes what counts as a duplicate (same name there). */
  function changeFolder(next: string) {
    setFolderId(next)
    setItems((current) =>
      current.map((item) =>
        item.state.kind === "queued"
          ? { ...item, checked: false, duplicate: undefined }
          : item
      )
    )
  }

  /**
   * Looks the not-yet-checked files up first (fingerprinted here, nothing
   * uploaded). Found duplicates wait for the user's choice; the rest upload.
   */
  async function start() {
    const unchecked = queued.filter((item) => !item.checked)
    if (unchecked.length === 0) {
      await runUploads(queued)
      return
    }
    setChecking(true)
    let found: Map<string, DuplicateMatchDto> = new Map()
    try {
      const hashes = await Promise.all(
        unchecked.map((item) => sha256Hex(item.file))
      )
      const results = await checkDuplicates(
        folderId || null,
        unchecked.map((item, i) => ({
          name: item.file.name,
          sha256: hashes[i],
        }))
      )
      found = new Map(
        results.flatMap((result, i) =>
          result.matches.length > 0
            ? [[unchecked[i].id, result.matches[0]]]
            : []
        ) as [string, DuplicateMatchDto][]
      )
      // No exact copy: look for documents with nearly the same text.
      for (const item of unchecked) {
        if (found.has(item.id) || !canCheckSimilar(item.file)) continue
        try {
          const similar = await findSimilarDocuments(item.file)
          if (similar.length > 0) found.set(item.id, similar[0])
        } catch {
          // a file whose text can't be read is just not compared
        }
      }
    } catch {
      // The check is a convenience - if it can't run, upload as before.
    } finally {
      setChecking(false)
    }
    const ids = new Set(unchecked.map((item) => item.id))
    const next = items.map((item) => {
      if (!ids.has(item.id)) return item
      const match = found.get(item.id)
      return {
        ...item,
        checked: true,
        notice: undefined,
        duplicate: match
          ? { match, choice: suggestedChoice(match) }
          : undefined,
      }
    })
    setItems(next)
    // Duplicates need a decision first; with none, carry straight on.
    if (found.size === 0) {
      await runUploads(next.filter((item) => item.state.kind === "queued"))
    }
  }

  function choose(id: string, choice: DuplicateChoice) {
    setItems((current) =>
      current.map((item) =>
        item.id === id && item.duplicate
          ? { ...item, duplicate: { ...item.duplicate, choice } }
          : item
      )
    )
  }

  function chooseForAll(choice: DuplicateChoice) {
    setItems((current) =>
      current.map((item) =>
        item.duplicate && item.state.kind === "queued"
          ? {
              ...item,
              duplicate: {
                ...item.duplicate,
                // A version only where the user may add one.
                choice:
                  choice === "version" && !item.duplicate.match.canAddVersion
                    ? "both"
                    : choice,
              },
            }
          : item
      )
    )
  }

  async function runUploads(targets: UploadItem[]) {
    setRunning(true)
    const queue = [...targets]
    async function worker() {
      for (let next = queue.shift(); next; next = queue.shift()) {
        const choice = next.duplicate?.choice
        if (choice === "skip") {
          update(next.id, { state: { kind: "skipped" } })
          continue
        }
        update(next.id, { state: { kind: "uploading" } })
        try {
          const ticket =
            choice === "version" && next.duplicate
              ? await uploadDocumentVersion(
                  next.duplicate.match.documentId,
                  next.file,
                  "Uploaded again"
                )
              : await uploadDocument(
                  next.file,
                  documentTypeId,
                  next.title.trim() || titleFromFilename(next.file.name),
                  folderId || null,
                  // Checked and nothing found: if the same file arrives meanwhile, ask again.
                  next.checked && !next.duplicate
                )
          update(next.id, {
            state:
              ticket.status === "pending"
                ? { kind: "scanning" }
                : choice === "version"
                  ? { kind: "versioned" }
                  : { kind: "uploaded" },
          })
        } catch (error) {
          if (
            error instanceof ApiError &&
            error.code === "DUPLICATE_UPLOADED_MEANWHILE"
          ) {
            // Someone added the same file a moment ago - check again and let the user choose.
            update(next.id, {
              state: { kind: "queued" },
              checked: false,
              notice:
                "The same file was just added by someone else. Upload again to choose what to do.",
            })
            continue
          }
          update(next.id, { state: outcomeOf(error) })
        }
      }
    }
    await Promise.all(
      Array.from({ length: Math.min(CONCURRENCY, queue.length) }, worker)
    )
    setRunning(false)
    onUploaded?.()
  }

  const queued = items.filter((i) => i.state.kind === "queued")
  const retryable = items.filter((i) => i.state.kind === "failed")
  const done = items.filter((i) =>
    ["uploaded", "versioned", "scanning", "skipped"].includes(i.state.kind)
  ).length
  const waitingForChoice = queued.filter((i) => i.duplicate)
  const problems = items.filter((i) =>
    ["blocked", "rejected", "failed"].includes(i.state.kind)
  ).length
  const canUpload =
    Boolean(documentTypeId) && queued.length > 0 && !running && !checking

  function handleOpenChange(next: boolean) {
    if (running || checking) return // don't close mid-upload
    onOpenChange(next)
  }

  return (
    <Sheet open={open} onOpenChange={handleOpenChange}>
      <SheetContent
        side="right"
        className="w-full gap-0 data-[side=right]:sm:max-w-[560px]"
      >
        <SheetHeader className="border-b border-border">
          <SheetTitle>Upload documents</SheetTitle>
          <SheetDescription>
            Upload many files at once as one document type. Every file is
            scanned for malware before it becomes a document.
          </SheetDescription>
        </SheetHeader>

        <div className="flex flex-1 flex-col gap-4 overflow-y-auto p-4">
          <div>
            <p className="mb-1.5 text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
              Document type
            </p>
            <SearchableSelect
              allLabel={types.isPending ? "Loading…" : "Choose a document type"}
              selected={documentTypeId}
              onChange={setDocumentTypeId}
              options={(types.data ?? []).map((t) => ({
                value: t.documentTypeId,
                label: t.typeName,
              }))}
              className="w-full"
            />
            {types.isError && (
              <p className="mt-1.5 text-[12.5px] text-[var(--rust)]">
                {types.error instanceof Error
                  ? types.error.message
                  : "Could not load document types."}
              </p>
            )}
            {types.data && types.data.length === 0 && (
              <p className="mt-1.5 text-[12.5px] text-muted-foreground">
                No active document types exist yet.
              </p>
            )}
          </div>

          <div>
            <p className="mb-1.5 text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
              Folder
            </p>
            <FolderSelect
              value={folderId}
              onChange={changeFolder}
              className="w-full"
              disabled={running || checking}
            />
          </div>

          <div
            onDragOver={(e) => {
              e.preventDefault()
              setDragOver(true)
            }}
            onDragLeave={() => setDragOver(false)}
            onDrop={(e) => {
              e.preventDefault()
              setDragOver(false)
              if (!running) addFiles(Array.from(e.dataTransfer.files))
            }}
            className={`rounded-lg border border-dashed p-6 text-center transition-colors ${
              dragOver
                ? "border-primary bg-primary/5"
                : "border-[var(--border-strong)]"
            }`}
          >
            <Upload className="mx-auto mb-2 size-5 text-muted-foreground" />
            <p className="text-[13px] text-foreground">Drag files here, or</p>
            <Button
              type="button"
              variant="outline"
              size="sm"
              className="mt-2"
              disabled={running}
              onClick={() => fileInputRef.current?.click()}
            >
              Choose files
            </Button>
            <input
              ref={fileInputRef}
              type="file"
              multiple
              className="hidden"
              onChange={(e) => {
                if (e.target.files) addFiles(Array.from(e.target.files))
                e.target.value = ""
              }}
            />
          </div>

          {waitingForChoice.length > 0 && !running && (
            <div className="flex flex-col gap-2 rounded-lg border border-[var(--gold)] bg-[var(--gold-tint)] p-3 text-[12.5px]">
              <p className="flex items-start gap-2 font-medium text-foreground">
                <Copy
                  size={14}
                  className="mt-0.5 shrink-0 text-[var(--gold)]"
                />
                {waitingForChoice.length === 1
                  ? "1 file is already in the DMS."
                  : `${waitingForChoice.length} files are already in the DMS.`}{" "}
                Choose what to do with each, then upload.
              </p>
              {waitingForChoice.length > 1 && (
                <div className="flex flex-wrap items-center gap-1.5 text-muted-foreground">
                  For all of them:
                  <Button
                    size="xs"
                    variant="outline"
                    onClick={() => chooseForAll("version")}
                  >
                    New version
                  </Button>
                  <Button
                    size="xs"
                    variant="outline"
                    onClick={() => chooseForAll("both")}
                  >
                    Keep both
                  </Button>
                  <Button
                    size="xs"
                    variant="outline"
                    onClick={() => chooseForAll("skip")}
                  >
                    Skip
                  </Button>
                </div>
              )}
            </div>
          )}

          {items.length > 0 && (
            <ul className="divide-y divide-border rounded-lg border border-border">
              {items.map((item) => (
                <li key={item.id} className="flex items-start gap-3 p-3">
                  <div className="min-w-0 flex-1">
                    {item.state.kind === "queued" ? (
                      <Input
                        value={item.title}
                        onChange={(e) =>
                          update(item.id, { title: e.target.value })
                        }
                        aria-label={`Title for ${item.file.name}`}
                        className="h-8 text-[13px]"
                      />
                    ) : (
                      <p className="truncate text-[13.5px] font-semibold text-foreground">
                        {item.title}
                      </p>
                    )}
                    <p className="mt-1 truncate font-mono text-[11.5px] text-muted-foreground">
                      {item.file.name} · {formatBytes(item.file.size)}
                    </p>
                    {"message" in item.state && (
                      <p className="mt-1 text-[12px] text-[var(--rust)]">
                        {item.state.message}
                      </p>
                    )}
                    {item.notice && item.state.kind === "queued" && (
                      <p className="mt-1 text-[12px] text-[var(--gold)]">
                        {item.notice}
                      </p>
                    )}
                    {item.duplicate && item.state.kind === "queued" && (
                      <DuplicateChoicePicker
                        match={item.duplicate.match}
                        choice={item.duplicate.choice}
                        disabled={running}
                        onChange={(choice) => choose(item.id, choice)}
                      />
                    )}
                  </div>
                  <Badge
                    variant={STATE_VARIANT[item.state.kind]}
                    className="shrink-0 gap-1.5 rounded-md"
                  >
                    {item.state.kind === "uploading" && (
                      <Loader2 className="size-3 animate-spin" />
                    )}
                    {STATE_LABEL[item.state.kind]}
                  </Badge>
                  {item.state.kind === "queued" && !running && (
                    <button
                      type="button"
                      aria-label={`Remove ${item.file.name}`}
                      onClick={() =>
                        setItems((current) =>
                          current.filter((i) => i.id !== item.id)
                        )
                      }
                      className="text-muted-foreground hover:text-foreground"
                    >
                      <X className="size-4" />
                    </button>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>

        <SheetFooter className="border-t border-border">
          {items.length > 0 && (
            <p className="text-[12.5px] text-muted-foreground">
              {items.length} file{items.length === 1 ? "" : "s"} · {done} done
              {problems > 0 && ` · ${problems} with problems`}
            </p>
          )}
          <div className="flex gap-2">
            {retryable.length > 0 && !running && (
              <Button
                type="button"
                variant="outline"
                onClick={() => {
                  retryable.forEach((i) =>
                    update(i.id, { state: { kind: "queued" } })
                  )
                  void runUploads(retryable)
                }}
              >
                <RotateCcw /> Retry failed ({retryable.length})
              </Button>
            )}
            <Button
              type="button"
              disabled={!canUpload}
              onClick={() => void start()}
              className="flex-1"
            >
              {running || checking ? (
                <Loader2 className="animate-spin" />
              ) : (
                <Upload />
              )}
              {checking
                ? "Checking for duplicates…"
                : running
                  ? "Uploading…"
                  : `Upload ${queued.length || ""} file${queued.length === 1 ? "" : "s"}`}
            </Button>
          </div>
          {!documentTypeId && queued.length > 0 && (
            <p className="text-[12px] text-muted-foreground">
              Choose a document type to upload.
            </p>
          )}
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}

const CHOICES: { value: DuplicateChoice; label: string }[] = [
  { value: "version", label: "New version" },
  { value: "both", label: "Keep both" },
  { value: "skip", label: "Skip" },
]

/** What's already there, and the three ways to go on. */
function DuplicateChoicePicker({
  match,
  choice,
  disabled,
  onChange,
}: {
  match: DuplicateMatchDto
  choice: DuplicateChoice
  disabled: boolean
  onChange: (choice: DuplicateChoice) => void
}) {
  return (
    <div className="mt-2 rounded-md border border-[var(--gold)]/60 bg-[var(--gold-tint)] p-2.5">
      <p className="text-[12px] leading-[1.45] text-foreground">
        {matchReason(match)}
      </p>
      <div
        role="radiogroup"
        aria-label="What to do with this file"
        className="mt-2 flex flex-wrap gap-1.5"
      >
        {CHOICES.map((option) => {
          const unavailable = option.value === "version" && !match.canAddVersion
          return (
            <button
              key={option.value}
              type="button"
              role="radio"
              aria-checked={choice === option.value}
              disabled={disabled || unavailable}
              title={
                unavailable
                  ? "You can't edit that document, so you can't add a version to it"
                  : option.value === "version"
                    ? `Becomes the next version of "${match.title}" - the current one stays in its history`
                    : option.value === "both"
                      ? "Upload it as a separate document"
                      : "Don't upload this file"
              }
              onClick={() => onChange(option.value)}
              className={cn(
                "rounded-md border px-2.5 py-1 text-[12px] font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50",
                choice === option.value
                  ? "border-primary bg-primary text-primary-foreground"
                  : "border-border bg-card text-foreground hover:border-primary/60"
              )}
            >
              {option.label}
            </button>
          )
        })}
      </div>
    </div>
  )
}
