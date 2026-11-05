"use client"

import { useState } from "react"
import { useRouter } from "next/navigation"
import {
  Bookmark,
  BookmarkMinus,
  Check,
  Download,
  ExternalLink,
  Eye,
  LayoutGrid,
  List,
  Pencil,
  X,
} from "lucide-react"
import { toast } from "sonner"

import { ActionDialog } from "@/components/folders/folder-dialogs"
import { SectionError } from "@/components/dashboard/dash-card"
import { DocumentTile, type ItemAction } from "@/components/documents/browser-items"
import { DocumentPreview } from "@/components/documents/document-preview"
import { Badge } from "@/components/ui/badge"
import { Input } from "@/components/ui/input"
import { LoadingState } from "@/components/ui/loading-state"
import { SearchInput } from "@/components/ui/search-input"
import { SortableTh } from "@/components/ui/sortable-th"
import { TablePagination } from "@/components/ui/table-pagination"
import { Textarea } from "@/components/ui/textarea"
import { friendlyMessage } from "@/lib/api-error"
import { useBookmarksList } from "@/lib/bookmarks/use-bookmarks"
import type { BookmarkedDocumentDto, BookmarkSortKey, SortDirection } from "@/lib/bookmarks/api"
import { downloadDocumentFile, type DocumentListItemDto } from "@/lib/documents/api"
import { setBrowserView, useBrowserView } from "@/lib/documents/use-browser-view"
import { saveDownloadedFile } from "@/lib/download-file"
import { useFolders } from "@/lib/folders/use-folders"
import { cn, formatDateTime } from "@/lib/utils"

const PAGE_SIZE = 25

/** One bookmark's note, shown as plain text with a pencil to edit it, or an input with Save/Cancel. */
function NoteCell({
  note,
  onSave,
}: {
  note: string | null
  onSave: (next: string | null) => Promise<void>
}) {
  const [editing, setEditing] = useState(false)
  const [value, setValue] = useState(note ?? "")
  const [saving, setSaving] = useState(false)

  if (!editing) {
    return (
      <button
        type="button"
        onClick={() => {
          setValue(note ?? "")
          setEditing(true)
        }}
        className="group flex max-w-[260px] items-center gap-1.5 text-left text-[13px]"
        aria-label={note ? "Edit note" : "Add a note"}
      >
        <span className={note ? "truncate text-foreground" : "truncate text-muted-foreground italic"}>
          {note || "Add a note…"}
        </span>
        <Pencil size={12} className="shrink-0 text-muted-foreground opacity-0 group-hover:opacity-100" />
      </button>
    )
  }

  async function save() {
    setSaving(true)
    try {
      await onSave(value.trim() === "" ? null : value.trim())
      setEditing(false)
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="flex max-w-[300px] items-center gap-1.5">
      <Input
        autoFocus
        value={value}
        maxLength={500}
        disabled={saving}
        onChange={(event) => setValue(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === "Enter") void save()
          if (event.key === "Escape") setEditing(false)
        }}
        className="h-8 text-[13px]"
        aria-label="Bookmark note"
      />
      <button
        type="button"
        onClick={() => void save()}
        disabled={saving}
        aria-label="Save note"
        className="flex size-7 shrink-0 items-center justify-center rounded text-primary hover:bg-primary/10 disabled:opacity-50"
      >
        <Check size={14} />
      </button>
      <button
        type="button"
        onClick={() => setEditing(false)}
        disabled={saving}
        aria-label="Cancel"
        className="flex size-7 shrink-0 items-center justify-center rounded text-muted-foreground hover:bg-muted disabled:opacity-50"
      >
        <X size={14} />
      </button>
    </div>
  )
}

/** Grid view's equivalent of {@link NoteCell}: no room for an inline editor on a tile, so this is a small dialog instead. */
function EditNoteDialog({
  item,
  onOpenChange,
  onSave,
}: {
  item: BookmarkedDocumentDto
  onOpenChange: (open: boolean) => void
  onSave: (next: string | null) => Promise<void>
}) {
  const [value, setValue] = useState(item.note ?? "")
  return (
    <ActionDialog
      open
      onOpenChange={onOpenChange}
      title={`Note on "${item.document.title}"`}
      description="Private to you - nobody else who can open this document sees it."
      confirmLabel="Save"
      pendingLabel="Saving…"
      onConfirm={async () => {
        try {
          await onSave(value.trim() === "" ? null : value.trim())
          return null
        } catch (error) {
          return friendlyMessage(error, "Could not save the note.")
        }
      }}
    >
      <Textarea
        autoFocus
        value={value}
        maxLength={500}
        rows={4}
        onChange={(event) => setValue(event.target.value)}
        aria-label="Bookmark note"
        placeholder="Add a note…"
      />
    </ActionDialog>
  )
}

/**
 * My Bookmarks (4.4 Bookmark Documents): the caller's own private shortcuts, server-paged, sorted and
 * searched (BD-FR-003). A bookmark whose document is in the recycle bin or no longer visible is left
 * off the list and counted in the notice below instead - it reappears on its own once the document
 * comes back (BD-FR-010).
 *
 * Grid and list views, the same toggle (and the same remembered preference, shared with My Documents
 * and Shared with me) as the rest of the document screens.
 */
export function BookmarksView() {
  const router = useRouter()
  const view = useBrowserView()
  const folders = useFolders()
  const [query, setQuery] = useState("")
  const [page, setPage] = useState(1)
  const [sort, setSort] = useState<BookmarkSortKey>("bookmarkedAt")
  const [dir, setDir] = useState<SortDirection>("desc")
  const [preview, setPreview] = useState<DocumentListItemDto | null>(null)
  const [editingNote, setEditingNote] = useState<BookmarkedDocumentDto | null>(null)

  const { page: data, isLoading, isError, refetch, updateNote, remove } = useBookmarksList({
    page: page - 1,
    size: PAGE_SIZE,
    sort,
    dir,
    q: query,
  })

  function toggleSort(key: BookmarkSortKey) {
    setPage(1)
    if (key === sort) {
      setDir((current) => (current === "asc" ? "desc" : "asc"))
    } else {
      setSort(key)
      setDir(key === "title" ? "asc" : "desc")
    }
  }

  async function download(row: DocumentListItemDto) {
    try {
      saveDownloadedFile(await downloadDocumentFile(row.documentId))
    } catch (error) {
      toast.error(friendlyMessage(error, "Could not download the document."))
    }
  }

  function documentActions(item: BookmarkedDocumentDto): ItemAction[] {
    const { document } = item
    return [
      { label: "Open", icon: Eye, onSelect: () => setPreview(document) },
      { label: "Download", icon: Download, onSelect: () => void download(document) },
      {
        label: "Details",
        icon: ExternalLink,
        onSelect: () => router.push(`/documents/${document.documentId}`),
      },
      { label: item.note ? "Edit note…" : "Add a note…", icon: Pencil, onSelect: () => setEditingNote(item) },
      {
        label: "Remove bookmark",
        icon: BookmarkMinus,
        danger: true,
        onSelect: () => void remove(document.documentId).catch(() => {}),
      },
    ]
  }

  const items = data?.items ?? []
  const totalPages = data ? Math.max(1, Math.ceil(data.total / PAGE_SIZE)) : 1

  const empty = query.trim()
    ? "None of your bookmarks match your search."
    : "You haven't bookmarked anything yet. Use the bookmark icon on any document - in My Documents, its preview, or its own page - to add one here."

  return (
    <div className="flex min-h-[calc(100dvh-300px)] min-w-0 flex-col">
      <div className="mb-4 flex flex-wrap items-center gap-2 rounded-lg border border-border bg-card px-3 py-2 shadow-[var(--ledger-shadow)]">
        <span className="inline-flex items-center gap-2 text-[14px] font-semibold">
          <Bookmark size={16} className="text-[var(--gold-foreground)]" />
          My Bookmarks
        </span>
        {!isLoading && !isError && data && (
          <span className="text-[12.5px] text-muted-foreground">
            {data.total} {data.total === 1 ? "bookmark" : "bookmarks"}
            {data.unavailableCount > 0 &&
              ` · ${data.unavailableCount} ${data.unavailableCount === 1 ? "bookmark is" : "bookmarks are"} currently unavailable`}
          </span>
        )}
        <div className="ml-auto flex flex-wrap items-center gap-2">
          <SearchInput
            value={query}
            onChange={(value) => {
              setQuery(value)
              setPage(1)
            }}
            placeholder="Search bookmarks…"
            aria-label="Search bookmarks"
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

      <p className="mb-4 text-[12.5px] text-muted-foreground">
        Bookmarks are private to you. To share a shortcut with others, pin the document instead.
      </p>

      {isLoading ? (
        <LoadingState message="Loading your bookmarks…" />
      ) : isError ? (
        <SectionError
          message="Your bookmarks couldn't be loaded."
          onRetry={() => void refetch()}
        />
      ) : items.length === 0 ? (
        <p className="flex flex-1 items-center justify-center rounded-lg border border-dashed border-[var(--border-strong)] px-6 py-12 text-center text-[13px] text-muted-foreground">
          {empty}
        </p>
      ) : view === "grid" ? (
        <ul className="grid grid-cols-[repeat(auto-fill,minmax(168px,1fr))] gap-3">
          {items.map((item) => (
            <DocumentTile
              key={item.document.documentId}
              row={item.document}
              checked={false}
              onOpen={() => setPreview(item.document)}
              actions={documentActions(item)}
              bookmarked
              flag={
                item.archived ? (
                  <Badge variant="muted" title="No longer current, but still here">
                    Archived
                  </Badge>
                ) : undefined
              }
            />
          ))}
        </ul>
      ) : (
        <div className="overflow-hidden rounded-lg border border-border bg-card shadow-[var(--ledger-shadow)]">
          <div className="overflow-x-auto">
            <table className="w-full min-w-[920px] border-collapse">
              <thead>
                <tr className="border-b border-border">
                  <SortableTh label="Title" active={sort === "title"} direction={dir} onClick={() => toggleSort("title")} />
                  <th className="px-3 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
                    Type
                  </th>
                  <th className="px-3 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
                    Folder
                  </th>
                  <SortableTh
                    label="Bookmarked"
                    active={sort === "bookmarkedAt"}
                    direction={dir}
                    onClick={() => toggleSort("bookmarkedAt")}
                  />
                  <th className="px-3 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
                    Note
                  </th>
                  <th className="px-3 py-2.5 text-right text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
                    Actions
                  </th>
                </tr>
              </thead>
              <tbody>
                {items.map(({ document, bookmarkedAt, note, archived }) => (
                  <tr key={document.documentId} className="border-b border-border last:border-b-0 hover:bg-muted/60">
                    <td className="px-3 py-3 text-[13px]">
                      <button
                        type="button"
                        onClick={() => setPreview(document)}
                        className="text-left font-semibold text-primary hover:underline"
                      >
                        {document.title}
                      </button>
                      {archived && (
                        <Badge variant="muted" className="ml-2" title="No longer current, but still here">
                          Archived
                        </Badge>
                      )}
                    </td>
                    <td className="px-3 py-3 text-[13px] text-muted-foreground">
                      {document.documentTypeName ?? "—"}
                    </td>
                    <td className="px-3 py-3 text-[13px] text-muted-foreground">
                      {document.folderId
                        ? (folders.byId.get(document.folderId)?.path ?? "—")
                        : "Not in a folder"}
                    </td>
                    <td className="px-3 py-3 font-mono text-[12.5px] text-muted-foreground">
                      {formatDateTime(bookmarkedAt)}
                    </td>
                    <td className="px-3 py-3">
                      <NoteCell note={note} onSave={(next) => updateNote(document.documentId, next)} />
                    </td>
                    <td className="px-3 py-3">
                      <div className="flex items-center justify-end gap-1">
                        <button
                          type="button"
                          onClick={() => router.push(`/documents/${document.documentId}`)}
                          aria-label={`Open ${document.title}'s page`}
                          title="Open document page"
                          className="flex size-7 items-center justify-center rounded text-muted-foreground hover:bg-muted hover:text-foreground"
                        >
                          <ExternalLink size={14} />
                        </button>
                        <button
                          type="button"
                          onClick={() => void remove(document.documentId).catch(() => {})}
                          aria-label={`Remove ${document.title} from bookmarks`}
                          title="Remove bookmark"
                          className="flex size-7 items-center justify-center rounded text-muted-foreground hover:bg-[var(--rust-tint)] hover:text-[var(--rust)]"
                        >
                          <Bookmark size={14} fill="currentColor" />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {totalPages > 1 && (
        <div className="mt-4">
          <TablePagination currentPage={page} totalPages={totalPages} onPageChange={setPage} />
        </div>
      )}

      {preview && (
        <DocumentPreview
          key={preview.documentId}
          row={preview}
          onOpenChange={(open) => !open && setPreview(null)}
          onDownload={() => void download(preview)}
        />
      )}

      {editingNote && (
        <EditNoteDialog
          key={editingNote.document.documentId}
          item={editingNote}
          onOpenChange={(open) => !open && setEditingNote(null)}
          onSave={(next) => updateNote(editingNote.document.documentId, next)}
        />
      )}
    </div>
  )
}
