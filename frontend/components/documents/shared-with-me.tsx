"use client"

import { useState } from "react"
import { useRouter } from "next/navigation"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import {
  Download,
  Eye,
  FolderOpen,
  LayoutGrid,
  List,
  ShieldCheck,
  Trash2,
  Users,
} from "lucide-react"
import { toast } from "sonner"

import { ConfirmActionDialog } from "@/components/bulk-import/confirm-action-dialog"
import {
  BrowserList,
  DocumentTile,
  FolderTile,
  type ItemAction,
} from "@/components/documents/browser-items"
import { DocumentPreview } from "@/components/documents/document-preview"
import {
  AccessSheet,
  type AccessTarget,
} from "@/components/folders/access-sheet"
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
  fetchSharedWithMe,
  type DocumentListItemDto,
} from "@/lib/documents/api"
import {
  setBrowserView,
  useBrowserView,
} from "@/lib/documents/use-browser-view"
import {
  fetchSharedFolders,
  hasAccess,
  type FolderDto,
  type FolderNode,
} from "@/lib/folders/api"
import { FOLDERS_KEY } from "@/lib/folders/use-folders"
import { cn } from "@/lib/utils"

const PAGE_SIZE = 25

/** A shared folder on its own - its subfolders are reached by opening it. */
function asNode(folder: FolderDto): FolderNode {
  return { ...folder, children: [], path: folder.name, depth: 0 }
}

/**
 * Shared with me: the folders and documents someone gave the caller access
 * to, by name or through one of their roles (V53/V54) - wherever they're
 * filed, including inside folders the caller can't otherwise open. Things
 * the caller created themselves aren't listed; they're theirs.
 */
export function SharedWithMe() {
  const router = useRouter()
  const queryClient = useQueryClient()
  const { hasPermission, hasAnyPermission } = usePermissions()
  const can = {
    delete: hasPermission(PermissionCodes.DOCM_DOCUMENTS_DELETE),
    seeAccess: hasAnyPermission(
      PermissionCodes.DSC_FOLDER_ACCESS_VIEW,
      PermissionCodes.DSC_FOLDER_ACCESS_ASSIGN
    ),
  }
  const view = useBrowserView()
  const [query, setQuery] = useState("")
  const [page, setPage] = useState(1)
  const [preview, setPreview] = useState<DocumentListItemDto | null>(null)
  const [access, setAccess] = useState<{
    target: AccessTarget
    key: number
  } | null>(null)
  const [deleting, setDeleting] = useState<DocumentListItemDto | null>(null)

  const folders = useQuery({
    queryKey: [...FOLDERS_KEY, "shared-with-me"],
    queryFn: fetchSharedFolders,
  })
  const documents = useQuery({
    queryKey: ["documents", "shared-with-me", query, page],
    queryFn: () =>
      fetchSharedWithMe({ q: query, page: page - 1, size: PAGE_SIZE }),
  })

  const term = query.trim().toLowerCase()
  const shownFolders = (folders.data ?? [])
    .filter((f) => !term || f.name.toLowerCase().includes(term))
    .map(asNode)
  const rows = documents.data?.content ?? []
  const loading = folders.isPending || documents.isPending

  function openFolder(folder: FolderNode) {
    router.push(`/documents?folder=${encodeURIComponent(folder.folderId)}`)
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
      { label: "Open", icon: FolderOpen, onSelect: () => openFolder(folder) },
    ]
    if (can.seeAccess)
      actions.push({
        label: "Manage access",
        icon: ShieldCheck,
        onSelect: () =>
          setAccess({
            target: { kind: "folder", id: folder.folderId, name: folder.name },
            key: Date.now(),
          }),
      })
    return actions
  }

  function documentActions(row: DocumentListItemDto): ItemAction[] {
    const actions: ItemAction[] = [
      { label: "Open", icon: Eye, onSelect: () => setPreview(row) },
      { label: "Download", icon: Download, onSelect: () => void download(row) },
    ]
    if (can.seeAccess)
      actions.push({
        label: "Manage access",
        icon: ShieldCheck,
        onSelect: () =>
          setAccess({
            target: { kind: "document", id: row.documentId, name: row.title },
            key: Date.now(),
          }),
      })
    if (can.delete && hasAccess(row.myAccess, "edit"))
      actions.push({
        label: "Delete",
        icon: Trash2,
        danger: true,
        onSelect: () => setDeleting(row),
      })
    return actions
  }

  const empty = term
    ? "Nothing shared with you matches your search."
    : "Nothing has been shared with you yet. When someone gives you access to a folder or document, it shows up here."

  return (
    <div className="min-w-0">
      <div className="mb-4 flex flex-wrap items-center gap-2 rounded-lg border border-border bg-card px-3 py-2 shadow-[var(--ledger-shadow)]">
        <span className="inline-flex items-center gap-2 text-[14px] font-semibold">
          <Users size={16} className="text-primary" />
          Shared with me
        </span>
        {!loading && (
          <span className="text-[12.5px] text-muted-foreground">
            {[
              shownFolders.length > 0 &&
                `${shownFolders.length} ${shownFolders.length === 1 ? "folder" : "folders"}`,
              (documents.data?.totalElements ?? 0) > 0 &&
                `${documents.data?.totalElements} ${documents.data?.totalElements === 1 ? "document" : "documents"}`,
            ]
              .filter(Boolean)
              .join(" · ")}
          </span>
        )}
        <div className="ml-auto flex flex-wrap items-center gap-2">
          <SearchInput
            value={query}
            onChange={(value) => {
              setQuery(value)
              setPage(1)
            }}
            placeholder="Search shared items…"
            aria-label="Search shared items"
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

      {loading ? (
        <LoadingState message="Loading…" />
      ) : folders.isError || documents.isError ? (
        <p className="text-[13px] text-[var(--rust)]">
          {friendlyMessage(
            folders.error ?? documents.error,
            "Could not load what's shared with you."
          )}
        </p>
      ) : view === "list" ? (
        <BrowserList
          folders={shownFolders}
          rows={rows}
          checked={new Set()}
          selectable={false}
          onToggle={() => {}}
          onToggleAll={() => {}}
          onOpenFolder={openFolder}
          onOpenDocument={(row) => setPreview(row)}
          folderActions={folderActions}
          documentActions={documentActions}
          empty={empty}
        />
      ) : shownFolders.length === 0 && rows.length === 0 ? (
        <p className="rounded-lg border border-dashed border-[var(--border-strong)] px-6 py-12 text-center text-[13px] text-muted-foreground">
          {empty}
        </p>
      ) : (
        <ul className="grid grid-cols-[repeat(auto-fill,minmax(168px,1fr))] gap-3">
          {shownFolders.map((folder) => (
            <FolderTile
              key={folder.folderId}
              folder={folder}
              onOpen={() => openFolder(folder)}
              actions={folderActions(folder)}
            />
          ))}
          {rows.map((row) => (
            <DocumentTile
              key={row.documentId}
              row={row}
              checked={false}
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

      {preview && (
        <DocumentPreview
          key={preview.documentId}
          row={preview}
          onOpenChange={(open) => !open && setPreview(null)}
          onDownload={() => void download(preview)}
        />
      )}
      {access && (
        <AccessSheet
          key={access.key}
          target={access.target}
          open
          onOpenChange={(open) => !open && setAccess(null)}
        />
      )}
      <ConfirmActionDialog
        open={deleting !== null}
        onOpenChange={(open) => !open && setDeleting(null)}
        title={deleting ? `Delete "${deleting.title}"?` : ""}
        description="It disappears from My Documents for everyone. The audit trail keeps a record of it."
        confirmLabel="Delete"
        pendingLabel="Deleting…"
        onConfirm={async () => {
          if (!deleting) return null
          try {
            await deleteDocument(deleting.documentId)
            toast.success(`Deleted "${deleting.title}".`)
            void queryClient.invalidateQueries({ queryKey: ["documents"] })
            void queryClient.invalidateQueries({ queryKey: FOLDERS_KEY })
            return null
          } catch (error) {
            return friendlyMessage(error, "Could not delete the document.")
          }
        }}
      />
    </div>
  )
}
