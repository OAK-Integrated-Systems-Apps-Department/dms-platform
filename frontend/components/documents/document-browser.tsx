"use client"

import { useRef, useState } from "react"
import { usePathname, useRouter, useSearchParams } from "next/navigation"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import {
  Download,
  FileSearch,
  Droplet,
  CirclePause,
  FileDown,
  FolderInput,
  FolderOpen,
  FolderPlus,
  FolderUp,
  History,
  Link2,
  Pin,
  PinOff,
  LayoutGrid,
  List,
  Pencil,
  Send,
  ScanLine,
  ShieldCheck,
  Trash2,
  UserRoundCog,
  Eye,
  Upload,
  FilePlus2,
} from "lucide-react"
import { toast } from "sonner"

import { ConfirmActionDialog } from "@/components/ui/confirm-action-dialog"
import {
  BrowserList,
  DocumentTile,
  FolderTile,
  type ItemAction,
} from "@/components/documents/browser-items"
import {
  countLabel,
  DropHint,
  useBrowserDragDrop,
  type DragItems,
} from "@/components/documents/browser-dnd"
import { BulkUploadSheet } from "@/components/documents/bulk-upload-sheet"
import { TemplatePickerSheet } from "@/components/documents/template-picker-sheet"
import { ReasonDialog } from "@/components/checkout/reason-dialog"
import { useChangeStatus } from "@/lib/retention/use-retention"
import { DocumentPreview } from "@/components/documents/document-preview"
import { VersionHistorySheet } from "@/components/documents/version-history-sheet"
import { WatermarkDialog } from "@/components/watermarks/watermark-dialog"
import { OcrScanDialog } from "@/components/ocr/ocr-scan-dialog"
import { FolderPath } from "@/components/documents/folder-path"
import {
  ActionDialog,
  FolderNameDialog,
} from "@/components/folders/folder-dialogs"
import {
  AccessSheet,
  type AccessTarget,
} from "@/components/folders/access-sheet"
import { FolderSelect } from "@/components/folders/folder-select"
import { SubmitToWorkflowDialog } from "@/components/workflow/submit-to-workflow-button"
import { Button } from "@/components/ui/button"
import { LoadingState } from "@/components/ui/loading-state"
import { SearchInput } from "@/components/ui/search-input"
import { TablePagination } from "@/components/ui/table-pagination"
import { friendlyMessage } from "@/lib/api-error"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { saveDownloadedFile } from "@/lib/download-file"
import { usePins } from "@/lib/pins/use-pins"
import {
  deleteDocument,
  downloadDocumentFile,
  fetchDocuments,
  moveDocuments,
  type DocumentListItemDto,
} from "@/lib/documents/api"
import { watermarkSupport } from "@/lib/watermarks/supported"
import { useWatermarkAvailability } from "@/lib/watermarks/use-watermark-availability"
import { useOcrAvailability } from "@/lib/documents/use-ocr-availability"
import {
  fromFolderPicker,
  type DroppedFile,
} from "@/lib/documents/dropped-files"
import { startZipDownload } from "@/lib/documents/upload-manager"
import {
  createFolder,
  deleteFolder,
  hasAccess,
  moveFolder,
  renameFolder,
  subtreeIds,
  type FolderNode,
} from "@/lib/folders/api"
import {
  setBrowserView,
  useBrowserView,
} from "@/lib/documents/use-browser-view"
import { FOLDERS_KEY, useFolders } from "@/lib/folders/use-folders"
import { useDocumentWorkflowStatuses } from "@/lib/workflow/use-document-workflow-statuses"
import { LinkToDialog } from "@/components/relationships/link-to-dialog"
import { TransferDialog } from "@/components/ownership/transfer-dialog"
import { useDocument } from "@/lib/documents/use-document"
import { cn } from "@/lib/utils"

const PAGE_SIZE = 25

/** Keys the upload panel opened by a drop, so each drop starts it fresh. */
let droppedUploads = 0

type Dialog =
  | { kind: "new-folder"; key: number }
  | { kind: "rename"; folder: FolderNode; key: number }
  | { kind: "move-folder"; folder: FolderNode; key: number }
  | { kind: "delete-folder"; folder: FolderNode }
  | { kind: "move-documents"; ids: string[]; key: number }
  | { kind: "access"; target: AccessTarget; key: number }
  | { kind: "delete-documents"; rows: DocumentListItemDto[] }
  | { kind: "versions"; row: DocumentListItemDto; key: number }
  | { kind: "submit-for-approval"; row: DocumentListItemDto; key: number }
  | { kind: "watermark"; row: DocumentListItemDto; key: number }
  | { kind: "ocr"; row: DocumentListItemDto; key: number }
  | { kind: "link-to"; rows: DocumentListItemDto[]; key: number }
  | { kind: "transfer-ownership"; row: DocumentListItemDto; key: number }
  | { kind: "mark-inactive"; row: DocumentListItemDto; key: number }
  | null

/**
 * My Documents, browsed folder by folder like a SharePoint library: the top
 * level shows the tenant's shared folders plus documents not filed in any
 * folder; opening a folder shows its subfolders and the documents in it, as
 * tiles (grid) or rows (list). Documents can be uploaded at the top level or
 * straight into a folder. Folders can be created,
 * renamed, moved and deleted (when empty); documents uploaded straight into
 * a folder, moved between folders, and downloaded - a folder, or several
 * documents, as one ZIP of their files. Every action is
 * shown only with its permission and checked again by the server.
 *
 * Drag and drop works as in Google Drive: files and folders dragged in from
 * the computer upload into the open folder (or the folder they're dropped
 * on, keeping their folder structure), and documents and folders dragged
 * onto a folder - or onto a folder in the path - move there, with Undo.
 */
export function DocumentBrowser() {
  // The open folder lives in the URL (?folder=…), so Back, refresh and
  // shared links work. Each folder gets a fresh view (search, page, selection).
  const params = useSearchParams()
  const folderId = params.get("folder")
  // "Documents that supersede this": ?relatedTo=<id>&relationshipType=<code>&direction=source|target|any
  // searches every folder for documents linked to that one.
  const relatedTo = params.get("relatedTo")
  const relationshipType = params.get("relationshipType")
  const rawDirection = params.get("direction")
  const direction =
    rawDirection === "source" || rawDirection === "target" || rawDirection === "any"
      ? rawDirection
      : undefined
  return (
    <FolderView
      key={`${folderId ?? "root"}|${relatedTo ?? ""}|${relationshipType ?? ""}|${direction ?? ""}`}
      folderId={folderId}
      related={
        relatedTo
          ? { relatedTo, relationshipType: relationshipType ?? undefined, direction }
          : null
      }
    />
  )
}

interface RelatedFilter {
  relatedTo: string
  relationshipType?: string
  direction?: "source" | "target" | "any"
}

function FolderView({
  folderId,
  related,
}: {
  folderId: string | null
  related: RelatedFilter | null
}) {
  const router = useRouter()
  const pathname = usePathname()
  const queryClient = useQueryClient()
  const { hasPermission, hasAnyPermission } = usePermissions()
  const pins = usePins()
  const markInactive = useChangeStatus()
  const can = {
    createFolder: hasPermission(PermissionCodes.DSC_FOLDERS_CREATE),
    renameMove: hasPermission(PermissionCodes.DSC_FOLDERS_RENAME_MOVE),
    deleteFolder: hasPermission(PermissionCodes.DSC_FOLDERS_DELETE),
    deleteDocuments: hasPermission(PermissionCodes.DOCM_DOCUMENTS_DELETE),
    upload: hasPermission(PermissionCodes.DOCM_DOCUMENTS_CREATE),
    moveDocuments: hasPermission(PermissionCodes.DOCM_DOCUMENTS_MOVE),
    export: hasPermission(PermissionCodes.FIE_BULK_EXPORT_EXECUTE),
    versions: hasPermission(PermissionCodes.DVC_VERSIONS_VIEW),
    restoreVersion: hasPermission(PermissionCodes.DVC_VERSIONS_RESTORE),
    submitForApproval: hasPermission(
      PermissionCodes.DLM_SUBMIT_DOCUMENTS_FOR_REVIEW
    ),
    viewDocuments: hasPermission(PermissionCodes.DOCM_DOCUMENTS_VIEW),
    manageRelationships: hasPermission(PermissionCodes.DRM_RELATIONSHIPS_CREATE),
    // The server decides whose documents a caller may move (admins any, a department head their people's).
    transferOwnership: hasPermission(PermissionCodes.DOT_TRANSFER_INITIATE),
    markInactive: hasPermission(PermissionCodes.CRM_MARK_INACTIVE),
    ocrTrigger: hasPermission(PermissionCodes.OS_OCR_TRIGGER),
    // Who can get into a folder (V53) - viewing the list or changing it.
    seeAccess: hasAnyPermission(
      PermissionCodes.DSC_FOLDER_ACCESS_VIEW,
      PermissionCodes.DSC_FOLDER_ACCESS_ASSIGN
    ),
  }

  // One cached probe for the whole screen - every row's menu reads the
  // same answer rather than asking per document.
  const watermark = useWatermarkAvailability().data ?? {
    present: false,
    usable: false,
    unavailableMessage: null,
    templates: [],
  }
  const ocrAvailable = useOcrAvailability().data ?? false

  const view = useBrowserView()
  const folders = useFolders()
  const relatedDocument = useDocument(related?.relatedTo ?? "")
  const [query, setQuery] = useState("")
  const [page, setPage] = useState(1)
  const [checked, setChecked] = useState<Set<string>>(new Set())
  const [dialog, setDialog] = useState<Dialog>(null)
  const [upload, setUpload] = useState<{
    key: number
    /** Dragged in from the computer. */
    files?: DroppedFile[]
    /** Where they were dropped, when not the open folder. */
    folderId?: string
  } | null>(null)
  const [preview, setPreview] = useState<DocumentListItemDto | null>(null)
  const [templatesOpen, setTemplatesOpen] = useState(false)
  const folderInputRef = useRef<HTMLInputElement | null>(null)

  const current = folderId ? folders.byId.get(folderId) : undefined
  // The top level is open to everyone; inside a folder, the caller's own level applies.
  const canEditHere = hasAccess(current?.myAccess, "edit")
  const documents = useQuery({
    queryKey: ["documents", "browse", current?.folderId, query, page, related],
    queryFn: () =>
      fetchDocuments({
        folderId: related ? null : (current?.folderId ?? null),
        all: related !== null,
        q: query,
        page: page - 1,
        size: PAGE_SIZE,
        ...(related ?? {}),
      }),
  })
  const rows = documents.data?.content ?? []
  // Where each document on this page stands in its newest approval - one call for the whole page.
  const approvals = useDocumentWorkflowStatuses(rows.map((r) => r.documentId))

  function open(next: string | null) {
    router.push(
      next ? `${pathname}?folder=${encodeURIComponent(next)}` : pathname,
      { scroll: false }
    )
  }

  function toggle(documentId: string) {
    const next = new Set(checked)
    if (next.has(documentId)) next.delete(documentId)
    else next.add(documentId)
    setChecked(next)
  }

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: FOLDERS_KEY })
    void queryClient.invalidateQueries({ queryKey: ["documents"] })
  }

  async function attempt(
    action: () => Promise<unknown>,
    success: string,
    fallback: string
  ): Promise<string | null> {
    try {
      await action()
      toast.success(success)
      refresh()
      return null
    } catch (error) {
      return friendlyMessage(error, fallback)
    }
  }

  /** A folder as one ZIP, its subfolders kept - prepared in the panel at the bottom right, then saved. */
  function downloadFolder(folder: FolderNode) {
    startZipDownload(
      { folderId: folder.folderId, includeSubfolders: true },
      `${folder.name}.zip`
    )
  }

  /** The selected documents: one is downloaded as it is, several as one ZIP. */
  function downloadSelected() {
    const selected = rows.filter((r) => checked.has(r.documentId))
    if (selected.length === 1) {
      void download(selected[0])
      return
    }
    startZipDownload(
      { documentIds: [...checked] },
      `${checked.size} documents.zip`
    )
  }

  async function download(row: DocumentListItemDto) {
    try {
      saveDownloadedFile(await downloadDocumentFile(row.documentId))
    } catch (error) {
      toast.error(friendlyMessage(error, "Could not download the document."))
    }
  }

  const canMoveDocument = (row: DocumentListItemDto) =>
    can.moveDocuments && hasAccess(row.myAccess, "edit")
  const canMoveFolder = (folder: FolderNode) =>
    can.renameMove && hasAccess(folder.myAccess, "manage")

  const dnd = useBrowserDragDrop({
    here: current ?? null,
    canUploadTo: (target) =>
      can.upload && !missing && hasAccess(target?.myAccess, "edit"),
    canMoveInto: (items, target) => {
      const targetId = target?.folderId ?? null
      if (!hasAccess(target?.myAccess, "edit")) return false
      // Never into itself or one of its own subfolders.
      if (targetId && items.folders.some((f) => subtreeIds(f).has(targetId)))
        return false
      // Somewhere new for at least one of them.
      return (
        (items.documents.length > 0 &&
          targetId !== (current?.folderId ?? null)) ||
        items.folders.some((f) => (f.parentFolderId ?? null) !== targetId)
      )
    },
    onUpload: (files, target) => void uploadDropped(files, target),
    onMove: (items, target) => void moveDragged(items, target),
  })

  /** A document's drag carries the whole selection when it's part of it. */
  function documentDrag(row: DocumentListItemDto) {
    if (!canMoveDocument(row)) return undefined
    const documents = checked.has(row.documentId)
      ? rows.filter((r) => checked.has(r.documentId) && canMoveDocument(r))
      : [row]
    return dnd.source({ documents, folders: [] })
  }

  function folderDrag(folder: FolderNode) {
    return canMoveFolder(folder)
      ? dnd.source({ documents: [], folders: [folder] })
      : undefined
  }

  async function uploadDropped(
    dropped: Promise<DroppedFile[]>,
    target: FolderNode | null
  ) {
    let files: DroppedFile[]
    try {
      files = await dropped
    } catch {
      toast.error("Could not read the files you dropped.")
      return
    }
    if (files.length === 0) {
      toast.error(
        "There are no files in what you dropped - empty folders aren't uploaded."
      )
      return
    }
    setUpload({ key: ++droppedUploads, files, folderId: target?.folderId })
  }

  async function moveDragged(items: DragItems, target: FolderNode | null) {
    const targetId = target?.folderId ?? null
    const from = current?.folderId ?? null
    const documents = targetId === from ? [] : items.documents
    const folders = items.folders.filter(
      (f) => (f.parentFolderId ?? null) !== targetId
    )
    const movedDocuments: string[] = []
    const movedFolders: FolderNode[] = []
    const failed: string[] = []
    if (documents.length > 0) {
      try {
        await moveDocuments(
          documents.map((d) => d.documentId),
          targetId
        )
        movedDocuments.push(...documents.map((d) => d.documentId))
      } catch (error) {
        failed.push(friendlyMessage(error, "Could not move the documents."))
      }
    }
    for (const folder of folders) {
      try {
        await moveFolder(folder.folderId, targetId)
        movedFolders.push(folder)
      } catch (error) {
        failed.push(
          `"${folder.name}": ${friendlyMessage(error, "could not be moved.")}`
        )
      }
    }
    failed.forEach((message) => toast.error(message))
    if (movedDocuments.length + movedFolders.length === 0) return
    if (movedDocuments.length > 0) setChecked(new Set())
    refresh()

    async function undo() {
      try {
        if (movedDocuments.length > 0) await moveDocuments(movedDocuments, from)
        for (const folder of movedFolders)
          await moveFolder(folder.folderId, folder.parentFolderId ?? null)
        toast.success("Moved back.")
      } catch (error) {
        toast.error(friendlyMessage(error, "Could not move them back."))
      }
      refresh()
    }
    const label = countLabel({
      documents: documents.filter((d) => movedDocuments.includes(d.documentId)),
      folders: movedFolders,
    })
    toast.success(`Moved ${label} to "${target?.name ?? "My Documents"}".`, {
      action: { label: "Undo", onClick: () => void undo() },
    })
  }

  function folderActions(folder: FolderNode): ItemAction[] {
    const actions: ItemAction[] = [
      {
        label: "Open",
        icon: FolderOpen,
        onSelect: () => open(folder.folderId),
      },
    ]
    if (can.export)
      actions.push({
        label: "Download",
        icon: Download,
        onSelect: () => downloadFolder(folder),
      })
    if (can.renameMove && hasAccess(folder.myAccess, "edit"))
      actions.push({
        label: "Rename",
        icon: Pencil,
        onSelect: () => setDialog({ kind: "rename", folder, key: Date.now() }),
      })
    if (can.renameMove && hasAccess(folder.myAccess, "manage"))
      actions.push({
        label: "Move to",
        icon: FolderInput,
        onSelect: () =>
          setDialog({ kind: "move-folder", folder, key: Date.now() }),
      })
    if (can.seeAccess)
      actions.push({
        label: "Manage access",
        icon: ShieldCheck,
        onSelect: () =>
          setDialog({
            kind: "access",
            target: { kind: "folder", id: folder.folderId, name: folder.name },
            key: Date.now(),
          }),
      })
    if (can.deleteFolder && hasAccess(folder.myAccess, "manage"))
      actions.push({
        label: "Delete",
        icon: Trash2,
        danger: true,
        onSelect: () => setDialog({ kind: "delete-folder", folder }),
      })
    return actions
  }

  function documentActions(row: DocumentListItemDto): ItemAction[] {
    const actions: ItemAction[] = [
      { label: "Open", icon: Eye, onSelect: () => setPreview(row) },
      {
        label: "View details",
        icon: FileSearch,
        onSelect: () => router.push(`/documents/${row.documentId}`),
      },
      { label: "Download", icon: Download, onSelect: () => void download(row) },
    ]
    // Submitting is offered only while nothing is already in approval - the same rule the
    // document page applies to SubmitToWorkflowButton, and the server checks it again.
    if (
      can.submitForApproval &&
      !approvals.isPending &&
      approvals.data?.get(row.documentId)?.status !== "in_progress"
    )
      actions.push({
        label: "Submit for approval",
        icon: Send,
        onSelect: () =>
          setDialog({
            kind: "submit-for-approval",
            row,
            key: Date.now(),
          }),
      })
    // PID-FR-001: pin from the list (and from search results, which are this list).
    // Pinning opens the audience sheet: you choose who sees the pin as part of pinning it.
    if (pins.canToggle(row.documentId)) {
      const mine = pins.isMine(row.documentId)
      actions.push({
        label: mine ? "Change who sees pin…" : "Pin…",
        icon: Pin,
        onSelect: () => pins.openPin(row),
      })
      if (mine && pins.canUnpin)
        actions.push({
          label: "Unpin",
          icon: PinOff,
          onSelect: () => void pins.unpin(row).catch(() => {}),
        })
    }
    if (can.versions)
      actions.push({
        label: "Version history",
        icon: History,
        onSelect: () => setDialog({ kind: "versions", row, key: Date.now() }),
      })
    // Every document the caller can open has a Related Documents panel.
    actions.push({
      label: "Relationships",
      icon: Link2,
      onSelect: () => router.push(`/documents/${row.documentId}#relationships`),
    })
    if (can.moveDocuments && hasAccess(row.myAccess, "edit"))
      actions.push({
        label: "Move to",
        icon: FolderInput,
        onSelect: () =>
          setDialog({
            kind: "move-documents",
            ids: [row.documentId],
            key: Date.now(),
          }),
      })
    if (can.manageRelationships && hasAccess(row.myAccess, "edit"))
      actions.push({
        label: "Link to…",
        icon: Link2,
        onSelect: () =>
          setDialog({ kind: "link-to", rows: [row], key: Date.now() }),
      })
    // 6.1: switch an active document off. Not offered under legal hold (its status is locked); normal listings only
    // ever hold active documents, so this is the only direction here - bringing one back is done from Inactive & Archive.
    if (can.markInactive && hasAccess(row.myAccess, "edit") && row.lockState !== "legal_hold")
      actions.push({
        label: "Mark inactive",
        icon: CirclePause,
        onSelect: () => setDialog({ kind: "mark-inactive", row, key: Date.now() }),
      })
    if (can.transferOwnership)
      actions.push({
        label: "Transfer ownership",
        icon: UserRoundCog,
        onSelect: () =>
          setDialog({ kind: "transfer-ownership", row, key: Date.now() }),
      })
    // Watermarking needs only document-view: the copy it produces is of a
    // document the caller can already read, and it never replaces the
    // original. The row is shown disabled rather than hidden for a file
    // type the renderer can't handle, so "why isn't this here?" has an
    // answer; the whole action disappears only when the feature is off.
    if (watermark.present && can.viewDocuments) {
      const support = watermarkSupport(row)
      actions.push({
        label: "Add watermark",
        icon: Droplet,
        disabled: !support.supported || !watermark.usable,
        hint: support.reason ?? watermark.unavailableMessage ?? undefined,
        onSelect: () => setDialog({ kind: "watermark", row, key: Date.now() }),
      })
    }
    // OCR re-run overwrites the document's OCR-sourced metadata, so it
    // needs edit access, not just view - the same pairing the backend
    // enforces (os.perform-ocr-extraction.trigger PLUS edit on the row).
    if (ocrAvailable && can.ocrTrigger && hasAccess(row.myAccess, "edit")) {
      actions.push({
        label: "OCR scan",
        icon: ScanLine,
        onSelect: () => setDialog({ kind: "ocr", row, key: Date.now() }),
      })
    }
    if (can.export)
      actions.push({
        label: "Export",
        icon: FileDown,
        onSelect: () =>
          startZipDownload({ documentIds: [row.documentId] }, row.title),
      })
    if (can.seeAccess)
      actions.push({
        label: "Manage access",
        icon: ShieldCheck,
        onSelect: () =>
          setDialog({
            kind: "access",
            target: { kind: "document", id: row.documentId, name: row.title },
            key: Date.now(),
          }),
      })
    if (can.deleteDocuments && hasAccess(row.myAccess, "edit"))
      actions.push({
        label: "Delete",
        icon: Trash2,
        danger: true,
        onSelect: () => setDialog({ kind: "delete-documents", rows: [row] }),
      })
    return actions
  }

  // Path to the current folder, top level first.
  const trail: FolderNode[] = []
  for (
    let node = current;
    node;
    node = node.parentFolderId
      ? folders.byId.get(node.parentFolderId)
      : undefined
  ) {
    trail.unshift(node)
  }
  const children = current ? current.children : folders.roots
  const folderQuery = query.trim().toLowerCase()
  const shownFolders = folderQuery
    ? children.filter((c) => c.name.toLowerCase().includes(folderQuery))
    : children
  // A link to a folder that was deleted (or never existed).
  const missing = folderId !== null && !folders.isPending && !current
  const loadingDocuments = !missing && documents.isPending
  const documentTotal = documents.data?.totalElements ?? 0
  const summary =
    folders.isPending || loadingDocuments || missing
      ? undefined
      : [
          children.length > 0 &&
            `${children.length} ${children.length === 1 ? "folder" : "folders"}`,
          documentTotal > 0 &&
            `${documentTotal} ${documentTotal === 1 ? "document" : "documents"}`,
        ]
          .filter(Boolean)
          .join(" · ") || undefined
  const emptyMessage = folderQuery
    ? "Nothing here matches your search."
    : current
      ? "This folder is empty. Drag files here, upload documents or create a folder."
      : "Nothing here yet. Drag files here, upload documents or create a folder to get started."

  return (
    // Fills the window down to the bottom, as in Google Drive, so files can be
    // dropped anywhere below the list too - not only on it.
    <div
      className="relative flex min-h-[calc(100dvh-300px)] min-w-0 flex-col"
      {...dnd.area.handlers}
    >
      {dnd.area.over && (
        <div
          aria-hidden="true"
          className="pointer-events-none absolute -inset-2 z-10 rounded-xl border-2 border-primary bg-primary/5"
        />
      )}
      <DropHint hint={dnd.hint} />
      {related && (
        <div className="mb-3 flex flex-wrap items-center gap-2 text-[12.5px]">
          <span className="flex items-center gap-1.5 rounded-btn border border-primary/40 bg-primary/5 px-2.5 py-1">
            {related.relationshipType === "SUPERSEDES" && related.direction === "source"
              ? "Documents that supersede"
              : "Documents related to"}{" "}
            <strong>{relatedDocument.data?.title ?? "this document"}</strong>
            <button
              type="button"
              aria-label="Clear the relationship filter"
              className="text-muted-foreground hover:text-foreground"
              onClick={() => router.push(pathname, { scroll: false })}
            >
              ×
            </button>
          </span>
        </div>
      )}
      <div className="mb-4 rounded-lg border border-border bg-card shadow-[var(--ledger-shadow)]">
        <div className="border-b border-border px-2.5 py-2">
          <FolderPath
            trail={trail}
            onOpen={open}
            summary={summary}
            dropTarget={dnd.target}
          />
        </div>
        <div className="flex flex-wrap items-center gap-2 px-2.5 py-2">
          {can.createFolder && canEditHere && !missing && (
            <Button
              variant="ghost"
              size="sm"
              onClick={() => setDialog({ kind: "new-folder", key: Date.now() })}
            >
              <FolderPlus />
              New folder
            </Button>
          )}
          {can.upload && canEditHere && !missing && (
            <Button
              variant="ghost"
              size="sm"
              onClick={() => setUpload({ key: Date.now() })}
            >
              <Upload />
              Upload
            </Button>
          )}
          {can.upload && canEditHere && !missing && (
            <Button
              variant="ghost"
              size="sm"
              onClick={() => folderInputRef.current?.click()}
            >
              <FolderUp />
              Upload folder
            </Button>
          )}
          <input
            // webkitdirectory isn't in React's types - set it on the element.
            ref={(input) => {
              folderInputRef.current = input
              input?.setAttribute("webkitdirectory", "")
            }}
            type="file"
            multiple
            hidden
            onChange={(e) => {
              const files = fromFolderPicker(e.target.files ?? [])
              e.target.value = ""
              if (files.length > 0) setUpload({ key: ++droppedUploads, files })
              else toast.error("That folder has no files to upload.")
            }}
          />
          {current && can.export && (
            <Button
              variant="ghost"
              size="sm"
              onClick={() => downloadFolder(current)}
            >
              <Download />
              Download
            </Button>
          )}
          {!missing && (
            <Button
              variant="ghost"
              size="sm"
              onClick={() => setTemplatesOpen(true)}
            >
              <FilePlus2 />
              New from template
            </Button>
          )}
          {current && can.seeAccess && (
            <Button
              variant="ghost"
              size="sm"
              onClick={() =>
                setDialog({
                  kind: "access",
                  target: {
                    kind: "folder",
                    id: current.folderId,
                    name: current.name,
                  },
                  key: Date.now(),
                })
              }
            >
              <ShieldCheck />
              Manage access
            </Button>
          )}
          <div className="ml-auto flex flex-wrap items-center gap-2">
            <SearchInput
              value={query}
              onChange={(value) => {
                setQuery(value)
                setPage(1)
              }}
              placeholder={current ? "Search this folder…" : "Search…"}
              aria-label={
                current ? "Search this folder" : "Search My Documents"
              }
              className="w-[220px] max-w-full"
            />
            <div
              role="radiogroup"
              aria-label="View"
              className="inline-flex rounded-md border border-border p-0.5"
            >
              {(
                [
                  { value: "grid", label: "Grid view", icon: LayoutGrid },
                  { value: "list", label: "List view", icon: List },
                ] as const
              ).map((option) => (
                <button
                  key={option.value}
                  type="button"
                  role="radio"
                  aria-checked={view === option.value}
                  aria-label={option.label}
                  title={option.label}
                  onClick={() => setBrowserView(option.value)}
                  className={cn(
                    "flex size-7 items-center justify-center rounded",
                    view === option.value
                      ? "bg-primary/15 text-foreground"
                      : "text-muted-foreground hover:text-foreground"
                  )}
                >
                  <option.icon size={15} />
                </button>
              ))}
            </div>
          </div>
        </div>
      </div>

      {checked.size > 0 && (
        <div className="mb-3 flex flex-wrap items-center gap-2 rounded-md border border-primary/40 bg-primary/5 px-3.5 py-2 text-[13px]">
          <span className="font-semibold">{checked.size} selected</span>
          {can.moveDocuments && canEditHere && (
            <Button
              variant="outline"
              size="sm"
              onClick={() =>
                setDialog({
                  kind: "move-documents",
                  ids: [...checked],
                  key: Date.now(),
                })
              }
            >
              <FolderInput />
              Move to
            </Button>
          )}
          {can.manageRelationships &&
            rows
              .filter((r) => checked.has(r.documentId))
              .every((r) => hasAccess(r.myAccess, "edit")) && (
              <Button
                variant="outline"
                size="sm"
                onClick={() =>
                  setDialog({
                    kind: "link-to",
                    rows: rows.filter((r) => checked.has(r.documentId)),
                    key: Date.now(),
                  })
                }
              >
                <Link2 />
                Link to…
              </Button>
            )}
          {can.export && (
            <Button variant="outline" size="sm" onClick={downloadSelected}>
              <Download />
              Download
            </Button>
          )}
          {can.deleteDocuments && (
            <Button
              variant="outline"
              size="sm"
              onClick={() =>
                setDialog({
                  kind: "delete-documents",
                  rows: rows.filter((r) => checked.has(r.documentId)),
                })
              }
            >
              <Trash2 />
              Delete
            </Button>
          )}
          <Button
            variant="outline"
            size="sm"
            onClick={() => setChecked(new Set())}
          >
            Clear
          </Button>
        </div>
      )}

      {folders.isPending || loadingDocuments ? (
        <LoadingState message="Loading…" />
      ) : missing ? (
        <div className="rounded-lg border border-dashed border-[var(--border-strong)] px-6 py-12 text-center">
          <p className="text-[13.5px] font-medium">
            This folder doesn&apos;t exist any more.
          </p>
          <p className="mt-1 text-[12.5px] text-muted-foreground">
            It may have been deleted or moved.{" "}
            <button
              type="button"
              className="text-primary hover:underline"
              onClick={() => open(null)}
            >
              Go to My Documents
            </button>
          </p>
        </div>
      ) : current && documents.isError ? (
        <p className="text-[13px] text-[var(--rust)]">
          {friendlyMessage(documents.error, "Could not load documents.")}
        </p>
      ) : view === "list" ? (
        <BrowserList
          folders={shownFolders}
          rows={rows}
          checked={checked}
          onToggle={toggle}
          onToggleAll={(on) => {
            const next = new Set(checked)
            rows.forEach((r) =>
              on ? next.add(r.documentId) : next.delete(r.documentId)
            )
            setChecked(next)
          }}
          onOpenFolder={(folder) => open(folder.folderId)}
          onOpenDocument={(row) => setPreview(row)}
          folderActions={folderActions}
          documentActions={documentActions}
          approvals={approvals.data}
          empty={emptyMessage}
          folderDrag={folderDrag}
          folderDrop={dnd.target}
          documentDrag={documentDrag}
        />
      ) : shownFolders.length === 0 && rows.length === 0 ? (
        <p className="flex min-h-[240px] flex-1 items-center justify-center rounded-lg border border-dashed border-[var(--border-strong)] px-6 py-12 text-center text-[13px] text-muted-foreground">
          {emptyMessage}
        </p>
      ) : (
        <ul className="grid grid-cols-[repeat(auto-fill,minmax(168px,1fr))] gap-3">
          {shownFolders.map((folder) => (
            <FolderTile
              key={folder.folderId}
              folder={folder}
              onOpen={() => open(folder.folderId)}
              actions={folderActions(folder)}
              drag={folderDrag(folder)}
              drop={dnd.target(folder)}
            />
          ))}
          {rows.map((row) => (
            <DocumentTile
              key={row.documentId}
              row={row}
              checked={checked.has(row.documentId)}
              onToggle={() => toggle(row.documentId)}
              onOpen={() => setPreview(row)}
              actions={documentActions(row)}
              drag={documentDrag(row)}
              approval={approvals.data?.get(row.documentId)}
            />
          ))}
        </ul>
      )}
      {(documents.data?.totalPages ?? 0) > 1 && (
        <TablePagination
          currentPage={page}
          totalPages={documents.data?.totalPages ?? 1}
          onPageChange={setPage}
        />
      )}

      {dialog?.kind === "new-folder" && (
        <FolderNameDialog
          key={dialog.key}
          open
          onOpenChange={(open) => !open && setDialog(null)}
          title={current ? `New folder in "${current.name}"` : "New folder"}
          confirmLabel="Create folder"
          onSubmit={(name) =>
            attempt(
              () => createFolder(name, current?.folderId ?? null),
              `Created "${name}".`,
              "Could not create the folder."
            )
          }
        />
      )}
      {dialog?.kind === "rename" && (
        <FolderNameDialog
          key={dialog.key}
          open
          onOpenChange={(open) => !open && setDialog(null)}
          title={`Rename "${dialog.folder.name}"`}
          initialName={dialog.folder.name}
          confirmLabel="Rename"
          onSubmit={(name) =>
            attempt(
              () => renameFolder(dialog.folder.folderId, name),
              `Renamed to "${name}".`,
              "Could not rename the folder."
            )
          }
        />
      )}
      {dialog?.kind === "move-folder" && (
        <MoveDialog
          key={dialog.key}
          title={`Move "${dialog.folder.name}"`}
          description="Choose where the folder (and everything in it) goes."
          noneLabel="Top level"
          initial={dialog.folder.parentFolderId ?? ""}
          exclude={subtreeIds(dialog.folder)}
          onClose={() => setDialog(null)}
          onMove={(target) =>
            attempt(
              () => moveFolder(dialog.folder.folderId, target || null),
              "Folder moved.",
              "Could not move the folder."
            )
          }
        />
      )}
      {dialog?.kind === "link-to" && (
        <LinkToDialog
          key={dialog.key}
          sources={dialog.rows}
          open
          onOpenChange={(next) => !next && setDialog(null)}
          onLinked={() => {
            setChecked(new Set())
            toast.success("Documents linked.")
          }}
        />
      )}
      {dialog?.kind === "mark-inactive" && (
        <ReasonDialog
          key={dialog.key}
          required={false}
          title={`Mark "${dialog.row.title}" inactive?`}
          description="It disappears from normal listings and search, and moves to Inactive & Archive for people allowed to see it. You can bring it back at any time."
          confirmLabel="Mark inactive"
          onConfirm={(reason) =>
            markInactive.mutateAsync({ documentId: dialog.row.documentId, status: "inactive", reason })
          }
          onOpenChange={(next) => !next && setDialog(null)}
        />
      )}
      {dialog?.kind === "transfer-ownership" && (
        <TransferDialog
          key={dialog.key}
          subject={{ kind: "document", documentId: dialog.row.documentId, title: dialog.row.title }}
          open
          onOpenChange={(next) => !next && setDialog(null)}
          onDone={() => {
            void queryClient.invalidateQueries({ queryKey: ["ownership"] })
            void queryClient.invalidateQueries({ queryKey: ["documents"] })
            toast.success("Ownership transferred.")
          }}
        />
      )}
      {dialog?.kind === "move-documents" && (
        <MoveDialog
          key={dialog.key}
          title={`Move ${dialog.ids.length} ${dialog.ids.length === 1 ? "document" : "documents"}`}
          description="Choose the folder to file them in."
          noneLabel="No folder (top level)"
          initial={current?.folderId ?? ""}
          onClose={() => setDialog(null)}
          onMove={(target) =>
            attempt(
              async () => {
                await moveDocuments(dialog.ids, target || null)
                setChecked(new Set())
              },
              "Documents moved.",
              "Could not move the documents."
            )
          }
        />
      )}
      <ConfirmActionDialog
        open={dialog?.kind === "delete-folder"}
        onOpenChange={(open) => !open && setDialog(null)}
        title={
          dialog?.kind === "delete-folder"
            ? `Delete "${dialog.folder.name}"?`
            : ""
        }
        description="Only an empty folder can be deleted - move its documents and subfolders out first."
        confirmLabel="Delete folder"
        pendingLabel="Deleting…"
        onConfirm={async () => {
          if (dialog?.kind !== "delete-folder") return null
          return attempt(
            () => deleteFolder(dialog.folder.folderId),
            "Folder deleted.",
            "Could not delete the folder."
          )
        }}
      />

      {dialog?.kind === "access" && (
        <AccessSheet
          key={dialog.key}
          target={dialog.target}
          open
          onOpenChange={(open) => !open && setDialog(null)}
        />
      )}

      <ConfirmActionDialog
        open={dialog?.kind === "delete-documents"}
        onOpenChange={(open) => !open && setDialog(null)}
        title={
          dialog?.kind === "delete-documents"
            ? dialog.rows.length === 1
              ? `Delete "${dialog.rows[0].title}"?`
              : `Delete ${dialog.rows.length} documents?`
            : ""
        }
        description={
          dialog?.kind === "delete-documents" && dialog.rows.length > 1
            ? "They move to the recycle bin, where they can be restored until they're deleted for good."
            : "It moves to the recycle bin, where it can be restored until it's deleted for good."
        }
        confirmLabel="Delete"
        pendingLabel="Deleting…"
        onConfirm={async () => {
          if (dialog?.kind !== "delete-documents") return null
          const failed: string[] = []
          for (const row of dialog.rows) {
            try {
              await deleteDocument(row.documentId)
            } catch (error) {
              failed.push(
                `${row.title}: ${friendlyMessage(error, "could not be deleted")}`
              )
            }
          }
          const deleted = dialog.rows.length - failed.length
          if (deleted > 0) {
            toast.success(
              `Deleted ${deleted} ${deleted === 1 ? "document" : "documents"}.`
            )
            setChecked(new Set())
            refresh()
          }
          return failed.length ? failed.join(" ") : null
        }}
      />

      {dialog?.kind === "versions" && (
        <VersionHistorySheet
          key={dialog.key}
          documentId={dialog.row.documentId}
          title={dialog.row.title}
          canUpload={can.upload && hasAccess(dialog.row.myAccess, "edit")}
          canRestore={
            can.restoreVersion && hasAccess(dialog.row.myAccess, "edit")
          }
          onOpenChange={(open) => !open && setDialog(null)}
          onChanged={refresh}
        />
      )}

      {dialog?.kind === "submit-for-approval" && (
        <SubmitToWorkflowDialog
          key={dialog.key}
          documentId={dialog.row.documentId}
          documentTitle={dialog.row.title}
          documentTypeId={dialog.row.documentTypeId}
          onOpenChange={(open) => !open && setDialog(null)}
          onSubmitted={refresh}
        />
      )}

      {dialog?.kind === "watermark" && (
        <WatermarkDialog
          key={dialog.key}
          documentId={dialog.row.documentId}
          documentTitle={dialog.row.title}
          templates={watermark.templates}
          open
          onOpenChange={(open) => !open && setDialog(null)}
        />
      )}

      {dialog?.kind === "ocr" && (
        <OcrScanDialog
          key={dialog.key}
          documentId={dialog.row.documentId}
          documentTitle={dialog.row.title}
          documentTypeId={dialog.row.documentTypeId}
          open
          onOpenChange={(open) => !open && setDialog(null)}
        />
      )}

      {preview && (
        <DocumentPreview
          key={preview.documentId}
          row={preview}
          onOpenChange={(open) => !open && setPreview(null)}
          onDownload={() => void download(preview)}
          onViewDetails={() => router.push(`/documents/${preview.documentId}`)}
          onWatermark={
            watermark.present && can.viewDocuments
              ? () => setDialog({ kind: "watermark", row: preview, key: Date.now() })
              : undefined
          }
          watermarkHint={
            watermarkSupport(preview).reason ?? watermark.unavailableMessage ?? undefined
          }
          watermarkDisabled={!watermarkSupport(preview).supported || !watermark.usable}
        />
      )}

      <TemplatePickerSheet
        open={templatesOpen}
        onOpenChange={setTemplatesOpen}
        folderId={current?.folderId ?? null}
        folderName={current ? `"${current.name}"` : "My Documents"}
        canCreate={can.upload && canEditHere}
        onCreated={refresh}
      />

      {upload && (
        <BulkUploadSheet
          key={upload.key}
          open
          onOpenChange={(open) => !open && setUpload(null)}
          initialFolderId={upload.folderId ?? current?.folderId}
          initialDrop={upload.files}
          canCreateFolders={can.createFolder}
          onUploaded={refresh}
        />
      )}
    </div>
  )
}

function MoveDialog({
  title,
  description,
  noneLabel,
  initial,
  exclude,
  onClose,
  onMove,
}: {
  title: string
  description: string
  noneLabel: string
  initial: string
  exclude?: Set<string>
  onClose: () => void
  onMove: (target: string) => Promise<string | null>
}) {
  const [target, setTarget] = useState(initial)
  return (
    <ActionDialog
      open
      onOpenChange={(open) => !open && onClose()}
      title={title}
      description={description}
      confirmLabel="Move"
      pendingLabel="Moving…"
      onConfirm={() => onMove(target)}
    >
      <FolderSelect
        value={target}
        onChange={setTarget}
        exclude={exclude}
        noneLabel={noneLabel}
        className="w-full"
        inline
      />
    </ActionDialog>
  )
}
