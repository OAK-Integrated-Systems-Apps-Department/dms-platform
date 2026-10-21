"use client"

import { useState } from "react"
import { useRouter } from "next/navigation"
import {
  Download,
  ExternalLink,
  Eye,
  LayoutGrid,
  List,
  Pin,
  PinOff,
} from "lucide-react"
import { toast } from "sonner"

import { SectionError } from "@/components/dashboard/dash-card"
import {
  BrowserList,
  DocumentTile,
  type ItemAction,
} from "@/components/documents/browser-items"
import { DocumentPreview } from "@/components/documents/document-preview"
import { Badge } from "@/components/ui/badge"
import { LoadingState } from "@/components/ui/loading-state"
import { SearchInput } from "@/components/ui/search-input"
import { friendlyMessage } from "@/lib/api-error"
import {
  downloadDocumentFile,
  type DocumentListItemDto,
} from "@/lib/documents/api"
import {
  setBrowserView,
  useBrowserView,
} from "@/lib/documents/use-browser-view"
import { saveDownloadedFile } from "@/lib/download-file"
import { usePins } from "@/lib/pins/use-pins"
import { cn } from "@/lib/utils"

/**
 * The signed-in user's pinned documents (PID-FR-002), laid out like "Shared with me": the same
 * toolbar, the same tiles or table, the same item menu. Open previews it; the menu downloads it,
 * opens its own page, or unpins it (PID-FR-005).
 *
 * Personal to the signed-in user: the server only ever returns their own pins, and only those
 * they can still open (PID-FR-006/008/009). An archived document stays, flagged.
 */
export function PinnedDocumentsView() {
  const router = useRouter()
  const view = useBrowserView()
  const { pins, limit, count, isLoading, isError, refetch, canUnpin, toggle } =
    usePins()
  const [query, setQuery] = useState("")
  const [preview, setPreview] = useState<DocumentListItemDto | null>(null)

  const term = query.trim().toLowerCase()
  const shown = pins.filter(
    (pin) =>
      !term ||
      [pin.document.title, pin.document.fileName, pin.document.documentTypeName]
        .filter(Boolean)
        .some((text) => (text as string).toLowerCase().includes(term))
  )
  const rows = shown.map((pin) => pin.document)
  const archived = new Set(
    pins.filter((pin) => pin.archived).map((pin) => pin.document.documentId)
  )

  async function download(row: DocumentListItemDto) {
    try {
      saveDownloadedFile(await downloadDocumentFile(row.documentId))
    } catch (error) {
      toast.error(friendlyMessage(error, "Could not download the document."))
    }
  }

  function documentActions(row: DocumentListItemDto): ItemAction[] {
    const actions: ItemAction[] = [
      { label: "Open", icon: Eye, onSelect: () => setPreview(row) },
      { label: "Download", icon: Download, onSelect: () => void download(row) },
      {
        label: "Details",
        icon: ExternalLink,
        onSelect: () => router.push(`/documents/${row.documentId}`),
      },
    ]
    if (canUnpin)
      actions.push({
        label: "Unpin",
        icon: PinOff,
        onSelect: () => void toggle(row),
      })
    return actions
  }

  const flag = (row: DocumentListItemDto) =>
    archived.has(row.documentId) ? (
      <Badge
        variant="muted"
        title="This document has been archived - it is still here, but no longer current"
      >
        Archived
      </Badge>
    ) : null

  const empty = term
    ? "None of your pinned documents match your search."
    : "Nothing pinned yet. Use Pin on any document in My Documents, in its preview, or on its own page, and it will be here."

  return (
    // Fills the window down to the bottom, like My Documents, so the empty state
    // is a full-height box instead of a thin strip under the toolbar.
    <div className="flex min-h-[calc(100dvh-300px)] min-w-0 flex-col">
      <div className="mb-4 flex flex-wrap items-center gap-2 rounded-lg border border-border bg-card px-3 py-2 shadow-[var(--ledger-shadow)]">
        <span className="inline-flex items-center gap-2 text-[14px] font-semibold">
          <Pin size={16} className="text-primary" />
          Pinned documents
        </span>
        {!isLoading && !isError && (
          <span className="text-[12.5px] text-muted-foreground">
            {limit !== null ? `${count} of ${limit} pinned` : `${count} pinned`}
            {archived.size > 0 && ` · ${archived.size} archived`}
          </span>
        )}
        <div className="ml-auto flex flex-wrap items-center gap-2">
          <SearchInput
            value={query}
            onChange={setQuery}
            placeholder="Search pinned…"
            aria-label="Search pinned documents"
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

      {isLoading ? (
        <LoadingState message="Loading your pinned documents…" />
      ) : isError ? (
        <SectionError
          message="Your pinned documents couldn't be loaded."
          onRetry={() => void refetch()}
        />
      ) : rows.length === 0 ? (
        <p className="flex flex-1 items-center justify-center rounded-lg border border-dashed border-[var(--border-strong)] px-6 py-12 text-center text-[13px] text-muted-foreground">
          {empty}
        </p>
      ) : view === "list" ? (
        <BrowserList
          folders={[]}
          rows={rows}
          checked={new Set()}
          selectable={false}
          onToggle={() => {}}
          onToggleAll={() => {}}
          onOpenFolder={() => {}}
          onOpenDocument={setPreview}
          folderActions={() => []}
          documentActions={documentActions}
          flag={flag}
          empty={empty}
        />
      ) : (
        <ul className="grid grid-cols-[repeat(auto-fill,minmax(168px,1fr))] gap-3">
          {rows.map((row) => (
            <DocumentTile
              key={row.documentId}
              row={row}
              checked={false}
              onOpen={() => setPreview(row)}
              actions={documentActions(row)}
              flag={flag(row)}
            />
          ))}
        </ul>
      )}

      {preview && (
        <DocumentPreview
          key={preview.documentId}
          row={preview}
          onOpenChange={(open) => !open && setPreview(null)}
          onDownload={() => void download(preview)}
        />
      )}
    </div>
  )
}
