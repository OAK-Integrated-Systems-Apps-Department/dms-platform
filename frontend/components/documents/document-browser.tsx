"use client"

import { useState } from "react"
import Link from "next/link"
import { usePathname, useRouter, useSearchParams } from "next/navigation"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import {
  Download,
  FileDown,
  FolderInput,
  FolderOpen,
  FolderPlus,
  LayoutGrid,
  List,
  Pencil,
  ShieldCheck,
  Trash2,
  Eye,
  Upload,
} from "lucide-react"
import { toast } from "sonner"

import { ConfirmActionDialog } from "@/components/bulk-import/confirm-action-dialog"
import {
  BrowserList,
  DocumentTile,
  FolderTile,
  type ItemAction,
} from "@/components/documents/browser-items"
import { BulkUploadSheet } from "@/components/documents/bulk-upload-sheet"
import { DocumentPreview } from "@/components/documents/document-preview"
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
import { Button } from "@/components/ui/button"
import { LoadingState } from "@/components/ui/loading-state"
import { SearchInput } from "@/components/ui/search-input"
import { TablePagination } from "@/components/ui/table-pagination"
import { friendlyMessage } from "@/lib/api-error"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { saveDownloadedFile } from "@/lib/bulk-imports/api"
import {
  deleteDocument,
  downloadDocumentFile,
  fetchDocuments,
  moveDocuments,
  type DocumentListItemDto,
} from "@/lib/documents/api"
import { requestExport } from "@/lib/export-jobs/api"
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
import { cn } from "@/lib/utils"

const PAGE_SIZE = 25

type Dialog =
  | { kind: "new-folder"; key: number }
  | { kind: "rename"; folder: FolderNode; key: number }
  | { kind: "move-folder"; folder: FolderNode; key: number }
  | { kind: "delete-folder"; folder: FolderNode }
  | { kind: "move-documents"; ids: string[]; key: number }
  | { kind: "access"; target: AccessTarget; key: number }
  | { kind: "delete-documents"; rows: DocumentListItemDto[] }
  | null

/**
 * My Documents, browsed folder by folder like a SharePoint library: the top
 * level shows the tenant's shared folders plus documents not filed in any
 * folder; opening a folder shows its subfolders and the documents in it, as
 * tiles (grid) or rows (list). Documents can be uploaded at the top level or
 * straight into a folder. Folders can be created,
 * renamed, moved and deleted (when empty); documents uploaded straight into
 * a folder, moved between folders, downloaded, or exported. Every action is
 * shown only with its permission and checked again by the server.
 */
export function DocumentBrowser() {
  // The open folder lives in the URL (?folder=…), so Back, refresh and
  // shared links work. Each folder gets a fresh view (search, page, selection).
  const folderId = useSearchParams().get("folder")
  return <FolderView key={folderId ?? "root"} folderId={folderId} />
}

function FolderView({ folderId }: { folderId: string | null }) {
  const router = useRouter()
  const pathname = usePathname()
  const queryClient = useQueryClient()
  const { hasPermission, hasAnyPermission } = usePermissions()
  const can = {
    createFolder: hasPermission(PermissionCodes.DSC_FOLDERS_CREATE),
    renameMove: hasPermission(PermissionCodes.DSC_FOLDERS_RENAME_MOVE),
    deleteFolder: hasPermission(PermissionCodes.DSC_FOLDERS_DELETE),
    deleteDocuments: hasPermission(PermissionCodes.DOCM_DOCUMENTS_DELETE),
    upload: hasPermission(PermissionCodes.DOCM_DOCUMENTS_CREATE),
    moveDocuments: hasPermission(PermissionCodes.DOCM_DOCUMENTS_MOVE),
    export: hasPermission(PermissionCodes.FIE_BULK_EXPORT_EXECUTE),
    // Who can get into a folder (V53) - viewing the list or changing it.
    seeAccess: hasAnyPermission(
      PermissionCodes.DSC_FOLDER_ACCESS_VIEW,
      PermissionCodes.DSC_FOLDER_ACCESS_ASSIGN
    ),
  }

  const view = useBrowserView()
  const folders = useFolders()
  const [query, setQuery] = useState("")
  const [page, setPage] = useState(1)
  const [checked, setChecked] = useState<Set<string>>(new Set())
  const [dialog, setDialog] = useState<Dialog>(null)
  const [upload, setUpload] = useState<{ key: number } | null>(null)
  const [preview, setPreview] = useState<DocumentListItemDto | null>(null)

  const current = folderId ? folders.byId.get(folderId) : undefined
  // The top level is open to everyone; inside a folder, the caller's own level applies.
  const canEditHere = hasAccess(current?.myAccess, "edit")
  const documents = useQuery({
    queryKey: ["documents", "browse", current?.folderId, query, page],
    queryFn: () =>
      fetchDocuments({
        folderId: current?.folderId ?? null,
        all: false,
        q: query,
        page: page - 1,
        size: PAGE_SIZE,
      }),
  })
  const rows = documents.data?.content ?? []

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

  async function exportDocuments(
    request: Parameters<typeof requestExport>[0],
    label: string
  ) {
    try {
      const job = await requestExport(request)
      toast.success(
        `Export of ${job.documentCount} ${job.documentCount === 1 ? "document" : "documents"} started.`,
        {
          description: `${label} - download it from My Exports when it's ready.`,
        }
      )
    } catch (error) {
      toast.error(friendlyMessage(error, "Could not start the export."))
    }
  }

  function exportFolder(folder: FolderNode) {
    void exportDocuments(
      {
        folderId: folder.folderId,
        includeSubfolders: true,
        metadataFormat: "xlsx",
        includeFiles: true,
      },
      folder.path
    )
  }

  async function download(row: DocumentListItemDto) {
    try {
      saveDownloadedFile(await downloadDocumentFile(row.documentId))
    } catch (error) {
      toast.error(friendlyMessage(error, "Could not download the document."))
    }
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
        label: "Export",
        icon: FileDown,
        onSelect: () => exportFolder(folder),
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
      { label: "Download", icon: Download, onSelect: () => void download(row) },
    ]
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
    if (can.export)
      actions.push({
        label: "Export",
        icon: FileDown,
        onSelect: () =>
          void exportDocuments(
            {
              documentIds: [row.documentId],
              metadataFormat: "xlsx",
              includeFiles: true,
            },
            row.title
          ),
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
      ? "This folder is empty. Upload documents or create a folder."
      : "Nothing here yet. Upload documents or create a folder to get started."

  return (
    <div className="min-w-0">
      <div className="mb-4 rounded-lg border border-border bg-card shadow-[var(--ledger-shadow)]">
        <div className="border-b border-border px-2.5 py-2">
          <FolderPath trail={trail} onOpen={open} summary={summary} />
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
          {current && can.export && (
            <Button
              variant="ghost"
              size="sm"
              onClick={() => exportFolder(current)}
            >
              <FileDown />
              Export folder
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
          {can.export && (
            <Button
              variant="outline"
              size="sm"
              onClick={() =>
                void exportDocuments(
                  {
                    documentIds: [...checked],
                    metadataFormat: "xlsx",
                    includeFiles: true,
                  },
                  `${checked.size} selected documents`
                )
              }
            >
              <FileDown />
              Export selected
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
          empty={emptyMessage}
        />
      ) : shownFolders.length === 0 && rows.length === 0 ? (
        <p className="rounded-lg border border-dashed border-[var(--border-strong)] px-6 py-12 text-center text-[13px] text-muted-foreground">
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
      <p className="mt-4 text-[12px] text-muted-foreground">
        Uploading many files with their metadata? Use{" "}
        <Link
          className="text-primary hover:underline"
          href="/documents/bulk-import"
        >
          Bulk Import
        </Link>
        .
      </p>

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
            ? "They disappear from My Documents for everyone. The audit trail keeps a record of them."
            : "It disappears from My Documents for everyone. The audit trail keeps a record of it."
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

      {preview && (
        <DocumentPreview
          key={preview.documentId}
          row={preview}
          onOpenChange={(open) => !open && setPreview(null)}
          onDownload={() => void download(preview)}
        />
      )}

      {upload && (
        <BulkUploadSheet
          key={upload.key}
          open
          onOpenChange={(open) => !open && setUpload(null)}
          initialFolderId={current?.folderId}
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
      />
    </ActionDialog>
  )
}
