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
import { describeAudience } from "@/lib/pins/audience"
import { usePins } from "@/lib/pins/use-pins"
import { cn } from "@/lib/utils"

/**
 * The documents pinned for the signed-in user (PID-FR-002), laid out like "Shared with me": the same
 * toolbar, the same tiles or table, the same item menu. Open previews it; the menu downloads it,
 * opens its own page, or - for pins they placed - changes who sees it or unpins it (PID-FR-005).
 *
 * Two kinds of pin appear: the ones this person placed, and the ones someone else placed for them.
 * The second kind says who pinned it and has no unpin: only the pinner can remove it. The server only
 * returns pins whose document the caller can still open (PID-FR-008/009). An archived document stays,
 * flagged.
 */
export function PinnedDocumentsView() {
  const router = useRouter()
  const view = useBrowserView()
  const {
    pins,
    limit,
    count,
    isLoading,
    isError,
    refetch,
    canPin,
    canUnpin,
    pinOf,
    openPin,
    unpin,
  } = usePins()
  const [query, setQuery] = useState("")
  const [filter, setFilter] = useState<"all" | "mine" | "others">("all")
  const [preview, setPreview] = useState<DocumentListItemDto | null>(null)

  const term = query.trim().toLowerCase()
  const othersCount = pins.length - count
  const shown = pins.filter(
    (pin) =>
      (filter === "all" || (filter === "mine") === pin.mine) &&
      (!term ||
        [
          pin.document.title,
          pin.document.fileName,
          pin.document.documentTypeName,
        ]
          .filter(Boolean)
          .some((text) => (text as string).toLowerCase().includes(term)))
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
    // Only the person who pinned it can change who sees it or unpin it.
    if (pinOf(row.documentId)?.mine) {
      if (canPin)
        actions.push({
          label: "Change who sees pin…",
          icon: Pin,
          onSelect: () => openPin(row),
        })
      if (canUnpin)
        actions.push({
          label: "Unpin",
          icon: PinOff,
          onSelect: () => void unpin(row).catch(() => {}),
        })
    }
    return actions
  }

  const flag = (row: DocumentListItemDto) => {
    const pin = pinOf(row.documentId)
    const isArchived = archived.has(row.documentId)
    // A document switched off by hand or archived by a retention rule already carries its own status badge on the row
    // (6.1); this flag is for the approval-archived case, so the two never say the same thing twice.
    const showArchivedFlag = isArchived && (row.lifecycleState ?? "active") === "active"
    if (!pin && !showArchivedFlag) return null
    return (
      <span className="inline-flex flex-wrap items-center gap-1">
        {showArchivedFlag && (
          <Badge
            variant="muted"
            title="This document has been archived - it is still here, but no longer current"
          >
            Archived
          </Badge>
        )}
        {pin && !pin.mine && (
          <Badge
            variant="muted"
            title={`${pin.pinnedByName} pinned this for you. Only they can unpin it.`}
          >
            Pinned by {pin.pinnedByName}
          </Badge>
        )}
        {pin?.mine && pin.audience && (
          <Badge variant="muted" title="Who sees your pin">
            {describeAudience(pin.audience.mode, pin.audience.count)}
          </Badge>
        )}
      </span>
    )
  }

  const empty = term
    ? "None of your pinned documents match your search."
    : filter === "others"
      ? "Nobody has pinned a document for you."
      : filter === "mine"
        ? "You haven't pinned anything. Use Pin on any document in My Documents, in its preview, or on its own page, and choose who sees it."
        : "Nothing pinned yet. Use Pin on any document in My Documents, in its preview, or on its own page, and choose who sees it."

  return (
    // Fills the window down to the bottom, like My Documents, so the empty state
    // is a full-height box instead of a thin strip under the toolbar.
    <div className="flex min-h-[calc(100dvh-300px)] min-w-0 flex-col">
      <div className="mb-4 flex flex-wrap items-center gap-2 rounded-lg border border-border bg-card px-4 py-2 shadow-[var(--ledger-shadow)]">
        <span className="inline-flex items-center gap-2 text-[14px] font-semibold">
          <Pin size={16} className="text-primary" />
          Pinned documents
        </span>
        {!isLoading && !isError && (
          <span className="text-[12.5px] text-muted-foreground">
            {limit !== null
              ? `${count} of ${limit} pinned by you`
              : `${count} pinned by you`}
            {othersCount > 0 && ` · ${othersCount} by others`}
            {archived.size > 0 && ` · ${archived.size} inactive or archived`}
          </span>
        )}
        <div className="ml-auto flex flex-wrap items-center gap-2">
          <div
            role="radiogroup"
            aria-label="Show"
            className="inline-flex rounded-btn border border-border p-0.5 text-[12.5px]"
          >
            {(
              [
                { value: "all", label: "All" },
                { value: "mine", label: "Pinned by me" },
                { value: "others", label: "Pinned by others" },
              ] as const
            ).map((option) => (
              <button
                key={option.value}
                type="button"
                role="radio"
                aria-checked={filter === option.value}
                onClick={() => setFilter(option.value)}
                className={cn(
                  "rounded-btn px-2.5 py-1",
                  filter === option.value
                    ? "bg-primary/15 font-medium text-foreground"
                    : "text-muted-foreground hover:text-foreground"
                )}
              >
                {option.label}
              </button>
            ))}
          </div>
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
            className="inline-flex rounded-btn border border-border p-0.5"
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
        <p className="flex flex-1 items-center justify-center rounded-lg border border-dashed border-[var(--border-strong)] px-6 py-12 text-center text-[13px] text-muted-foreground bg-card">
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
