"use client"

import { LoadFailed } from "@/components/ui/load-failed"
import { useRef, useState } from "react"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"
import {
  Copy,
  Download,
  Folder,
  Loader2,
  RotateCcw,
  Upload,
  X,
} from "lucide-react"

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
import { downloadTemplate } from "@/components/document-types/document-type-templates-tab"
import { friendlyMessage } from "@/lib/api-error"
import { fetchTemplates, TEMPLATES_KEY } from "@/lib/documents/templates"
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
import type { DroppedFile } from "@/lib/documents/dropped-files"
import {
  enqueueUploads,
  type UploadRequest,
} from "@/lib/documents/upload-manager"
import { createFolder } from "@/lib/folders/api"
import { FOLDERS_KEY, useFolders } from "@/lib/folders/use-folders"
import { cn } from "@/lib/utils"
import { useSelectableDocumentTypes } from "@/lib/documents/use-selectable-document-types"
import { formatBytes } from "@/lib/quarantine/quarantine-badges"

/** The document type last uploaded with, offered again next time (this browser only). */
const LAST_TYPE_KEY = "oakdms.upload.lastDocumentType"

function lastDocumentType(): string {
  try {
    return window.localStorage.getItem(LAST_TYPE_KEY) ?? ""
  } catch {
    return ""
  }
}

function rememberDocumentType(documentTypeId: string) {
  try {
    window.localStorage.setItem(LAST_TYPE_KEY, documentTypeId)
  } catch {
    // only a convenience
  }
}

/** Only what happens here - uploading itself is shown in the uploads panel. */
type ItemState =
  | { kind: "queued" }
  | { kind: "rejected"; message: string }
  | { kind: "failed"; message: string }

interface UploadItem {
  id: string
  file: File
  /** The folders it was in inside a dropped folder - recreated under the chosen folder. */
  dir: string[]
  /** That recreated folder, once it exists; undefined = the chosen folder. */
  targetFolderId?: string
  title: string
  state: ItemState
  /** Checked against what's already in the DMS for the chosen folder. */
  checked: boolean
  /** Already there - and what to do about it. */
  duplicate?: { match: DuplicateMatchDto; choice: DuplicateChoice }
}

const STATE_LABEL: Record<ItemState["kind"], string> = {
  queued: "Ready",
  rejected: "Not accepted",
  failed: "Failed",
}

let nextItemId = 0

function toItems(files: DroppedFile[]): UploadItem[] {
  return files.map(({ file, dir }) => ({
    id: `upload-${++nextItemId}`,
    file,
    dir,
    title: titleFromFilename(file.name),
    state: { kind: "queued" },
    checked: false,
  }))
}

function titleFromFilename(name: string): string {
  const dot = name.lastIndexOf(".")
  return (dot > 0 ? name.slice(0, dot) : name).trim() || name
}

const sameName = (a: string, b: string) =>
  a.trim().toLocaleLowerCase() === b.trim().toLocaleLowerCase()

/** "Invoice" -> "Invoice (2)", "Invoice (2)" -> "Invoice (3)" - for a copy kept beside the original. */
function nextCopyName(title: string): string {
  const numbered = /^(.*) \((\d+)\)$/.exec(title.trim())
  return numbered
    ? `${numbered[1]} (${Number(numbered[2]) + 1})`
    : `${title.trim()} (2)`
}

/**
 * The item with this duplicate choice. "Keep both" needs a name of its own, so
 * two documents side by side can be told apart - suggested here as "Name (2)"
 * while the title still matches the existing document's; the user can change it.
 */
function withChoice(item: UploadItem, choice: DuplicateChoice): UploadItem {
  if (!item.duplicate) return item
  const title =
    choice === "both" && sameName(item.title, item.duplicate.match.title)
      ? nextCopyName(item.duplicate.match.title)
      : item.title
  return { ...item, title, duplicate: { ...item.duplicate, choice } }
}

/** "Keep both" with the existing document's own name - not allowed, it would be indistinguishable. */
function needsNewName(item: UploadItem): boolean {
  return (
    item.state.kind === "queued" &&
    item.duplicate?.choice === "both" &&
    sameName(
      item.title || titleFromFilename(item.file.name),
      item.duplicate.match.title
    )
  )
}

/** The file renamed after its title (same extension), so a kept copy's file name matches it too. */
function fileNamed(file: File, title: string): File {
  const dot = file.name.lastIndexOf(".")
  const name = title.trim() + (dot > 0 ? file.name.slice(dot) : "")
  return name === file.name
    ? file
    : new File([file], name, {
        type: file.type,
        lastModified: file.lastModified,
      })
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
  /** Files (and the folders they were in) dragged in from the computer. Read once on mount. */
  initialDrop?: DroppedFile[]
  /** May create folders - needed to keep the structure of a dropped folder. */
  canCreateFolders?: boolean
  /** Folder preselected (e.g. the one being browsed); "" or absent = the top level of My Documents. Read once on mount. */
  initialFolderId?: string
  /** Called once every file handed to the uploads panel has finished, e.g. to refresh a list. */
  onUploaded?: () => void
}

/**
 * Upload many files at once, all as one document type (bulk upload without
 * a ZIP). Here the files get their document type, folder (recreating any
 * dropped folders) and a duplicate check; then they're handed to the
 * uploads panel at the bottom right (UploadsPanel), which sends each through
 * the normal, malware-scanned `POST /api/v1/documents` with a progress bar
 * while the user carries on. Per-file metadata and template-based import
 * are the separate Bulk Import feature (Module 2.1).
 */
export function BulkUploadSheet({
  open,
  onOpenChange,
  initialFiles,
  initialDrop,
  canCreateFolders = false,
  initialFolderId,
  onUploaded,
}: BulkUploadSheetProps) {
  const [chosenType, setDocumentTypeId] = useState(lastDocumentType)
  const [folderId, setFolderId] = useState(initialFolderId ?? "")
  const [items, setItems] = useState<UploadItem[]>(() =>
    toItems([
      ...(initialFiles ?? []).map((file) => ({ file, dir: [] })),
      ...(initialDrop ?? []),
    ])
  )
  const [checking, setChecking] = useState(false)
  const [dragOver, setDragOver] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const types = useSelectableDocumentTypes(open)
  const folders = useFolders(open)
  const queryClient = useQueryClient()
  const templates = useQuery({
    queryKey: [...TEMPLATES_KEY, chosenType],
    queryFn: () => fetchTemplates(chosenType),
    enabled: open && Boolean(chosenType),
  })
  // A remembered type that's no longer selectable isn't offered.
  const documentTypeId = types.data?.some(
    (t) => t.documentTypeId === chosenType
  )
    ? chosenType
    : ""
  const keepsStructure = items.some((item) => item.dir.length > 0)

  function addFiles(files: File[]) {
    setItems((current) => [
      ...current,
      ...toItems(files.map((file) => ({ file, dir: [] }))),
    ])
  }

  /** Where an item goes: its recreated folder, or the chosen one. */
  function targetOf(item: UploadItem): string | null {
    return item.targetFolderId ?? (folderId || null)
  }

  /**
   * Recreates the dropped folders under the chosen folder - reusing one that
   * already has the name, as Drive does - and points each file at its own.
   * Without permission to create folders, everything goes into the chosen
   * folder instead.
   */
  async function placeInFolders(all: UploadItem[]): Promise<UploadItem[]> {
    const made = new Map<string, string>()
    const broken = new Map<string, string>()
    let created = false
    const placed: UploadItem[] = []
    for (const item of all) {
      if (
        !canCreateFolders ||
        item.state.kind !== "queued" ||
        item.dir.length === 0 ||
        item.targetFolderId
      ) {
        placed.push(item)
        continue
      }
      let parent: string | null = folderId || null
      let failure: string | undefined
      for (let depth = 1; depth <= item.dir.length && !failure; depth++) {
        const key = item.dir.slice(0, depth).join("/")
        failure = broken.get(key)
        if (failure) break
        let id = made.get(key)
        if (!id) {
          const name = item.dir[depth - 1].trim()
          const siblings = parent
            ? (folders.byId.get(parent)?.children ?? [])
            : folders.roots
          id = siblings.find(
            (f) => f.name.toLowerCase() === name.toLowerCase()
          )?.folderId
          if (!id) {
            try {
              id = (await createFolder(name, parent)).folderId
              created = true
            } catch (error) {
              failure = `Could not create the folder "${name}": ${friendlyMessage(error, "unknown error")}`
              broken.set(key, failure)
              break
            }
          }
          made.set(key, id)
        }
        parent = id
      }
      placed.push(
        failure
          ? { ...item, state: { kind: "failed", message: failure } }
          : { ...item, targetFolderId: parent ?? undefined }
      )
    }
    if (created) void queryClient.invalidateQueries({ queryKey: FOLDERS_KEY })
    return placed
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
          ? {
              ...item,
              checked: false,
              duplicate: undefined,
              targetFolderId: undefined,
            }
          : item
      )
    )
  }

  /**
   * Looks the not-yet-checked files up first (fingerprinted here, nothing
   * uploaded). Found duplicates wait for the user's choice; the rest upload.
   */
  async function start() {
    rememberDocumentType(documentTypeId)
    setChecking(true)
    // Dropped folders first, so each file is checked against its own folder.
    const all = keepsStructure ? await placeInFolders(items) : items
    const ready = all.filter((item) => item.state.kind === "queued")
    const unchecked = ready.filter((item) => !item.checked)
    if (unchecked.length === 0) {
      setChecking(false)
      handOff(all)
      return
    }
    const found = new Map<string, DuplicateMatchDto>()
    try {
      const byFolder = new Map<string | null, UploadItem[]>()
      for (const item of unchecked) {
        const target = targetOf(item)
        byFolder.set(target, [...(byFolder.get(target) ?? []), item])
      }
      for (const [target, group] of byFolder) {
        const hashes = await Promise.all(
          group.map((item) => sha256Hex(item.file))
        )
        const results = await checkDuplicates(
          target,
          group.map((item, i) => ({
            name: item.file.name,
            sha256: hashes[i],
          }))
        )
        results.forEach((result, i) => {
          if (result.matches.length > 0)
            found.set(group[i].id, result.matches[0])
        })
      }
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
    const next = all.map((item) => {
      if (!ids.has(item.id)) return item
      const match = found.get(item.id)
      const checkedItem: UploadItem = {
        ...item,
        checked: true,
        duplicate: match
          ? { match, choice: suggestedChoice(match) }
          : undefined,
      }
      return match
        ? withChoice(checkedItem, suggestedChoice(match))
        : checkedItem
    })
    // Duplicates need a decision first; with none, carry straight on.
    if (found.size === 0) handOff(next)
    else setItems(next)
  }

  function choose(id: string, choice: DuplicateChoice) {
    setItems((current) =>
      current.map((item) =>
        item.id === id && item.duplicate ? withChoice(item, choice) : item
      )
    )
  }

  function chooseForAll(choice: DuplicateChoice) {
    setItems((current) =>
      current.map((item) =>
        item.duplicate && item.state.kind === "queued"
          ? withChoice(
              item,
              // A version only where the user may add one.
              choice === "version" && !item.duplicate.match.canAddVersion
                ? "both"
                : choice
            )
          : item
      )
    )
  }

  /**
   * Sends the ready files to the uploads panel - they upload there while the
   * user carries on - and closes, unless a folder couldn't be created for
   * some of them. "Skip" duplicates are dropped.
   */
  function handOff(all: UploadItem[]) {
    const nameOf = (id: string | null) =>
      id ? (folders.byId.get(id)?.name ?? "Folder") : "My Documents"
    const sending = all.filter(
      (item) =>
        item.state.kind === "queued" && item.duplicate?.choice !== "skip"
    )
    enqueueUploads(
      sending.map((item) => {
        const duplicate = item.duplicate
        if (duplicate?.choice === "version") {
          const request: UploadRequest = {
            kind: "version",
            file: item.file,
            documentId: duplicate.match.documentId,
            comment: "Uploaded again",
          }
          return {
            request,
            folderId: duplicate.match.folderId,
            folderName: duplicate.match.folderName ?? "My Documents",
          }
        }
        const target = targetOf(item)
        const title = item.title.trim() || titleFromFilename(item.file.name)
        const request: UploadRequest = {
          kind: "new",
          // A copy kept beside the original takes its new name as its file name too.
          file:
            duplicate?.choice === "both"
              ? fileNamed(item.file, title)
              : item.file,
          documentTypeId,
          title,
          folderId: target,
          // Checked and nothing found: if the same file arrives meanwhile, refuse rather than duplicate.
          refuseDuplicates: item.checked && !duplicate,
          ...(duplicate?.choice === "both"
            ? { duplicateOfDocumentId: duplicate.match.documentId }
            : {}),
        }
        return {
          request,
          folderId: target,
          folderName: item.targetFolderId
            ? (item.dir.at(-1) ?? nameOf(target))
            : nameOf(target),
        }
      }),
      onUploaded
    )
    const tooLarge = all.filter((item) => item.state.kind === "rejected")
    if (tooLarge.length > 0)
      toast.error(
        `${tooLarge.length} ${tooLarge.length === 1 ? "file was" : "files were"} left out - larger than the upload limit.`
      )
    const failed = all.filter((item) => item.state.kind === "failed")
    if (failed.length === 0) onOpenChange(false)
    else setItems(failed)
  }

  /** Files whose folder couldn't be created - through the folder step again. */
  async function retry() {
    setChecking(true)
    const again = items.map((item) =>
      item.state.kind === "failed"
        ? { ...item, state: { kind: "queued" } as ItemState }
        : item
    )
    const placed = keepsStructure ? await placeInFolders(again) : again
    setChecking(false)
    handOff(placed)
  }

  const queued = items.filter((i) => i.state.kind === "queued")
  const retryable = items.filter((i) => i.state.kind === "failed")
  const waitingForChoice = queued.filter((i) => i.duplicate)
  const problems = items.filter((i) => i.state.kind !== "queued").length
  const unnamedCopies = queued.filter(needsNewName).length
  const canUpload =
    Boolean(documentTypeId) &&
    queued.length > 0 &&
    !checking &&
    unnamedCopies === 0

  function handleOpenChange(next: boolean) {
    if (checking) return // don't close while folders are being made
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
              <LoadFailed error={types.error} fallback="Could not load document types." quiet />
            )}
            {types.data && types.data.length === 0 && (
              <p className="mt-1.5 text-[12.5px] text-muted-foreground">
                No active document types exist yet.
              </p>
            )}
            {documentTypeId && (templates.data ?? []).length > 0 && (
              <div className="mt-2 rounded-md bg-muted/50 px-3 py-2 text-[12.5px]">
                <p className="mb-1 text-muted-foreground">
                  Templates for this type - fill one in, then upload it here:
                </p>
                <ul className="flex flex-col gap-0.5">
                  {(templates.data ?? []).map((template) => (
                    <li key={template.templateId}>
                      <button
                        type="button"
                        onClick={() => void downloadTemplate(template)}
                        className="inline-flex items-center gap-1.5 font-medium text-primary hover:underline"
                      >
                        <Download size={13} aria-hidden="true" />
                        {template.name}
                        <span className="font-normal text-muted-foreground">
                          {template.fileName && `(${template.fileName})`}
                        </span>
                      </button>
                    </li>
                  ))}
                </ul>
              </div>
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
              disabled={checking}
            />
            {keepsStructure && (
              <p className="mt-1.5 text-[12px] text-muted-foreground">
                {canCreateFolders
                  ? "The folders you dropped are recreated inside this one, with each file in its own folder."
                  : "You can't create folders, so every file goes straight into this folder."}
              </p>
            )}
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
              if (!checking) addFiles(Array.from(e.dataTransfer.files))
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
              disabled={checking}
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

          {waitingForChoice.length > 0 && !checking && (
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
                    {item.dir.length > 0 && canCreateFolders && (
                      <p className="mt-0.5 flex items-center gap-1 truncate text-[11.5px] text-muted-foreground">
                        <Folder size={12} className="shrink-0" />
                        {item.dir.join(" / ")}
                      </p>
                    )}
                    {"message" in item.state && (
                      <p className="mt-1 text-[12px] text-[var(--rust)]">
                        {item.state.message}
                      </p>
                    )}
                    {item.duplicate && item.state.kind === "queued" && (
                      <DuplicateChoicePicker
                        match={item.duplicate.match}
                        choice={item.duplicate.choice}
                        disabled={checking}
                        needsNewName={needsNewName(item)}
                        onChange={(choice) => choose(item.id, choice)}
                      />
                    )}
                  </div>
                  <Badge
                    variant={item.state.kind === "queued" ? "outline" : "rust"}
                    className="shrink-0 rounded-md"
                  >
                    {STATE_LABEL[item.state.kind]}
                  </Badge>
                  {item.state.kind === "queued" && !checking && (
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
              {items.length} file{items.length === 1 ? "" : "s"}
              {problems > 0 && ` · ${problems} with problems`}
            </p>
          )}
          <div className="flex gap-2">
            {retryable.length > 0 && !checking && (
              <Button
                type="button"
                variant="outline"
                onClick={() => void retry()}
              >
                <RotateCcw /> Try again ({retryable.length})
              </Button>
            )}
            <Button
              type="button"
              disabled={!canUpload}
              onClick={() => void start()}
              className="flex-1"
            >
              {checking ? <Loader2 className="animate-spin" /> : <Upload />}
              {checking
                ? "Checking for duplicates…"
                : `Upload ${queued.length || ""} file${queued.length === 1 ? "" : "s"}`}
            </Button>
          </div>
          {!documentTypeId && queued.length > 0 && (
            <p className="text-[12px] text-muted-foreground">
              Choose a document type to upload.
            </p>
          )}
          {unnamedCopies > 0 && (
            <p className="text-[12px] text-[var(--rust)]">
              {unnamedCopies === 1
                ? "1 file kept beside an existing document needs a different name."
                : `${unnamedCopies} files kept beside existing documents need different names.`}
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
  needsNewName,
  onChange,
}: {
  match: DuplicateMatchDto
  choice: DuplicateChoice
  disabled: boolean
  /** "Keep both" chosen but the name is still the existing document's. */
  needsNewName: boolean
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
                "border transition-colors disabled:cursor-not-allowed disabled:opacity-50 h-[38px] rounded-btn px-4 text-[13px] font-semibold inline-flex items-center justify-center",
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
      {choice === "both" && (
        <p
          className={cn(
            "mt-2 text-[12px] leading-[1.45]",
            needsNewName ? "text-[var(--rust)]" : "text-muted-foreground"
          )}
        >
          {needsNewName
            ? `Give it a different name from "${match.title}" in the title box above, so the two documents can be told apart.`
            : "Uploaded as a separate document under the name above - change it if you like."}
        </p>
      )}
    </div>
  )
}
