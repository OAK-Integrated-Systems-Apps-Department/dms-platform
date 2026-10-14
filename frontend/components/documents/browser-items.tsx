"use client"

import { useEffect, useId, useRef, useState, type ReactNode } from "react"
import {
  File,
  FileArchive,
  FileAudio,
  FileImage,
  FileSpreadsheet,
  FileText,
  FileVideo,
  Lock,
  Mail,
  MoreHorizontal,
  Presentation,
  type LucideIcon,
} from "lucide-react"

import type {
  DragBinding,
  DropBinding,
} from "@/components/documents/browser-dnd"
import type { DocumentListItemDto } from "@/lib/documents/api"
import type { FolderNode } from "@/lib/folders/api"
import { PinnedIndicator } from "@/components/pins/pin-button"
import { formatBytes } from "@/lib/quarantine/quarantine-badges"
import { cn, formatDateTime, formatFriendlyDate } from "@/lib/utils"

/** One entry of an item's "…" menu. */
export interface ItemAction {
  label: string
  icon: LucideIcon
  onSelect: () => void
  danger?: boolean
}

interface FileKind {
  icon: LucideIcon
  /** Tile preview colour - the tint and the strong colour of the icon. */
  tone: string
  label: string
}

const KINDS: { match: RegExp; kind: FileKind }[] = [
  {
    match: /^pdf$/,
    kind: {
      icon: FileText,
      tone: "text-[#d13438] bg-[#d13438]/10",
      label: "PDF",
    },
  },
  {
    match: /^(docx?|odt|rtf)$/,
    kind: {
      icon: FileText,
      tone: "text-[#2b7cd3] bg-[#2b7cd3]/10",
      label: "Word",
    },
  },
  {
    match: /^(xlsx?|ods|csv)$/,
    kind: {
      icon: FileSpreadsheet,
      tone: "text-[#217346] bg-[#217346]/10",
      label: "Excel",
    },
  },
  {
    match: /^(pptx?|odp)$/,
    kind: {
      icon: Presentation,
      tone: "text-[#c43e1c] bg-[#c43e1c]/10",
      label: "PowerPoint",
    },
  },
  {
    match: /^(png|jpe?g|gif|bmp|tiff?|webp|heic)$/,
    kind: {
      icon: FileImage,
      tone: "text-[#8764b8] bg-[#8764b8]/10",
      label: "Image",
    },
  },
  {
    match: /^(eml|msg)$/,
    kind: {
      icon: Mail,
      tone: "text-[#0f6cbd] bg-[#0f6cbd]/10",
      label: "Email",
    },
  },
  {
    match: /^(zip|7z|rar|tar|gz)$/,
    kind: {
      icon: FileArchive,
      tone: "text-[#986f0b] bg-[#986f0b]/10",
      label: "Archive",
    },
  },
  {
    match: /^(mp4|mov|avi|mkv|webm)$/,
    kind: {
      icon: FileVideo,
      tone: "text-[#b4009e] bg-[#b4009e]/10",
      label: "Video",
    },
  },
  {
    match: /^(mp3|wav|m4a|ogg)$/,
    kind: {
      icon: FileAudio,
      tone: "text-[#038387] bg-[#038387]/10",
      label: "Audio",
    },
  },
  {
    match: /^(txt|md|xml|json|html?)$/,
    kind: {
      icon: FileText,
      tone: "text-muted-foreground bg-muted",
      label: "Text",
    },
  },
]
const OTHER: FileKind = {
  icon: File,
  tone: "text-muted-foreground bg-muted",
  label: "File",
}

function extensionOf(fileName: string | null) {
  const dot = fileName?.lastIndexOf(".") ?? -1
  return dot >= 0 && fileName ? fileName.slice(dot + 1).toLowerCase() : ""
}

export function fileKind(fileName: string | null): FileKind {
  const ext = extensionOf(fileName)
  return KINDS.find((k) => k.match.test(ext))?.kind ?? OTHER
}

/** Subfolders plus documents directly in the folder. */
export function itemCount(folder: FolderNode) {
  return folder.children.length + folder.documentCount
}

function itemsLabel(count: number) {
  if (count === 0) return "Empty"
  return `${count} ${count === 1 ? "item" : "items"}`
}

/** "12 minutes ago", with the exact date and time on hover. */
export function FriendlyDate({ iso }: { iso: string }) {
  return (
    <time dateTime={iso} title={formatDateTime(iso)}>
      {formatFriendlyDate(iso)}
    </time>
  )
}

/**
 * A two-panel folder in the theme colour, SharePoint style: a back panel with
 * its tab, a sheet of paper peeking out, and a solid front panel that carries
 * the item count. `count` is left off for the small list-view icon.
 */
export function FolderGlyph({
  count,
  size = 88,
  className,
}: {
  count?: number
  size?: number
  className?: string
}) {
  const id = useId()
  const label =
    count === undefined || count === 0
      ? null
      : count > 999
        ? "999+"
        : String(count)
  return (
    <svg
      width={size}
      height={(size * 52) / 64}
      viewBox="0 0 64 52"
      aria-hidden="true"
      className={cn("shrink-0", className)}
    >
      <defs>
        <linearGradient id={`${id}-shine`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#ffffff" stopOpacity="0.28" />
          <stop offset="1" stopColor="#ffffff" stopOpacity="0" />
        </linearGradient>
      </defs>
      {/* back panel with its tab */}
      <path
        d="M4 7a4 4 0 0 1 4-4h14.3a4 4 0 0 1 2.9 1.2L29.6 9H56a4 4 0 0 1 4 4v33a4 4 0 0 1-4 4H8a4 4 0 0 1-4-4z"
        fill="var(--primary)"
        opacity="0.7"
      />
      {/* sheets of paper peeking out */}
      <rect
        x="9"
        y="11"
        width="46"
        height="24"
        rx="2"
        fill="#ffffff"
        opacity="0.55"
      />
      <rect
        x="12"
        y="13.5"
        width="40"
        height="22"
        rx="2"
        fill="#ffffff"
        opacity="0.95"
      />
      {/* front panel */}
      <path
        d="M2 21a4 4 0 0 1 4-4h52a4 4 0 0 1 4 4v25a4 4 0 0 1-4 4H6a4 4 0 0 1-4-4z"
        fill="var(--primary)"
      />
      <path
        d="M2 21a4 4 0 0 1 4-4h52a4 4 0 0 1 4 4v25a4 4 0 0 1-4 4H6a4 4 0 0 1-4-4z"
        fill={`url(#${id}-shine)`}
      />
      {label && (
        <text
          x="55"
          y="44"
          textAnchor="end"
          fontSize="10"
          fontWeight="600"
          fill="var(--primary-foreground)"
          style={{ fontVariantNumeric: "tabular-nums" }}
        >
          {label}
        </text>
      )}
    </svg>
  )
}

const MENU_WIDTH = 184
const MENU_ROW = 36

/**
 * "…" button with a small menu of actions; closes on outside click, Escape,
 * scroll or resize. The menu is fixed to the viewport so a tile's rounded
 * clipping or the list's scroll box can't cut it off, and it opens upwards
 * when there's no room below.
 */
export function ItemMenu({
  label,
  actions,
  className,
  buttonLabel,
}: {
  label: string
  actions: ItemAction[]
  className?: string
  /** The button's accessible name; defaults to "More actions for {label}". */
  buttonLabel?: string
}) {
  const [position, setPosition] = useState<{
    top: number
    left: number
  } | null>(null)
  const ref = useRef<HTMLDivElement>(null)
  const open = position !== null

  useEffect(() => {
    if (!open) return
    function close() {
      setPosition(null)
    }
    function onPointerDown(event: MouseEvent) {
      if (ref.current && !ref.current.contains(event.target as Node)) close()
    }
    function onKey(event: KeyboardEvent) {
      if (event.key === "Escape") close()
    }
    document.addEventListener("mousedown", onPointerDown)
    document.addEventListener("keydown", onKey)
    window.addEventListener("scroll", close, true)
    window.addEventListener("resize", close)
    return () => {
      document.removeEventListener("mousedown", onPointerDown)
      document.removeEventListener("keydown", onKey)
      window.removeEventListener("scroll", close, true)
      window.removeEventListener("resize", close)
    }
  }, [open])

  function toggle(button: HTMLButtonElement) {
    if (open) {
      setPosition(null)
      return
    }
    const rect = button.getBoundingClientRect()
    const height = actions.length * MENU_ROW + 10
    const below = rect.bottom + 4 + height <= window.innerHeight - 8
    setPosition({
      top: below ? rect.bottom + 4 : Math.max(8, rect.top - 4 - height),
      left: Math.min(
        window.innerWidth - MENU_WIDTH - 8,
        Math.max(8, rect.right - MENU_WIDTH)
      ),
    })
  }

  if (actions.length === 0) return null
  return (
    <div
      ref={ref}
      // Stay visible while open, even when the pointer leaves the tile.
      className={cn("relative", className, open && "z-40 opacity-100")}
      // Kept deliberately, unlike the identical-looking stops removed from
      // the select popovers: this one is load-bearing. The whole tile around
      // it opens the document on click, so without this, using the menu
      // would also open what the menu is about. It is a propagation barrier,
      // not an interaction handler - the real control is the <button> below,
      // and a keyboard activation of it synthesises a click that this stops
      // just the same. Sonar's S1082 flags it as a click handler on a
      // non-interactive element; adding a keyboard handler here would be
      // meaningless, so mark it a false positive rather than "fixing" it.
      onClick={(e) => e.stopPropagation()}
    >
      <button
        type="button"
        aria-label={buttonLabel ?? `More actions for ${label}`}
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={(e) => toggle(e.currentTarget)}
        className={cn(
          "flex size-7 items-center justify-center rounded-md text-muted-foreground hover:bg-muted hover:text-foreground",
          open && "bg-muted text-foreground"
        )}
      >
        <MoreHorizontal size={16} />
      </button>
      {position && (
        <div
          role="menu"
          style={{ top: position.top, left: position.left, width: MENU_WIDTH }}
          className="fixed z-50 overflow-hidden rounded-lg border border-border bg-popover py-1 shadow-[0_8px_24px_rgba(0,0,0,0.18)]"
        >
          {actions.map((action) => (
            <button
              key={action.label}
              type="button"
              role="menuitem"
              onClick={() => {
                setPosition(null)
                action.onSelect()
              }}
              style={{ height: MENU_ROW }}
              className={cn(
                "flex w-full items-center gap-2.5 px-3 text-left text-[13px] whitespace-nowrap",
                action.danger
                  ? "text-destructive hover:bg-destructive/10"
                  : "text-foreground hover:bg-muted"
              )}
            >
              <action.icon size={15} className="shrink-0" />
              {action.label}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

const TILE =
  "group relative flex h-[196px] flex-col overflow-hidden rounded-lg border bg-card text-left shadow-[var(--ledger-shadow)] transition-colors"

/** A folder with something held over it that can be dropped there. */
const DROP_OVER = "border-primary bg-primary/10 ring-2 ring-primary"

export function FolderTile({
  folder,
  onOpen,
  actions,
  drag,
  drop,
}: {
  folder: FolderNode
  onOpen: () => void
  actions: ItemAction[]
  /** Drag it onto another folder to move it. */
  drag?: DragBinding
  /** Drop documents, folders or files from the computer onto it. */
  drop?: DropBinding
}) {
  return (
    <li
      {...drag}
      {...drop?.handlers}
      className={cn(
        TILE,
        drop?.over
          ? DROP_OVER
          : "border-border hover:border-primary/50 hover:bg-muted/40"
      )}
    >
      <button
        type="button"
        onClick={onOpen}
        title={folder.path}
        className="flex flex-1 flex-col items-center justify-center gap-1 px-3 pt-4"
      >
        <FolderGlyph count={itemCount(folder)} />
      </button>
      {folder.restricted && (
        <span
          title="Only specific people and roles can open this folder"
          className="absolute top-2.5 left-2.5 inline-flex items-center gap-1 rounded-full bg-muted px-2 py-0.5 text-[11px] font-medium text-muted-foreground"
        >
          <Lock size={11} aria-hidden="true" />
          Restricted
        </span>
      )}
      <button type="button" onClick={onOpen} className="px-3.5 pb-3 text-left">
        <p className="truncate text-[13.5px] font-semibold">{folder.name}</p>
        <p className="mt-0.5 truncate text-[12px] text-muted-foreground">
          {itemsLabel(itemCount(folder))} ·{" "}
          <FriendlyDate iso={folder.updatedAt ?? folder.createdAt} />
        </p>
      </button>
      <ItemMenu
        label={folder.name}
        actions={actions}
        className="absolute top-2 right-2 opacity-0 group-focus-within:opacity-100 group-hover:opacity-100"
      />
    </li>
  )
}

export function DocumentTile({
  row,
  checked,
  onToggle,
  onOpen,
  actions,
  drag,
}: {
  row: DocumentListItemDto
  checked: boolean
  /** Omit for a listing without selection. */
  onToggle?: () => void
  /** Opens the preview. */
  onOpen: () => void
  actions: ItemAction[]
  /** Drag it (with the rest of the selection) onto a folder to move it. */
  drag?: DragBinding
}) {
  const kind = fileKind(row.fileName)
  const ext = extensionOf(row.fileName)
  return (
    <li
      {...drag}
      className={cn(
        TILE,
        checked
          ? "border-primary ring-1 ring-primary"
          : "border-border hover:border-primary/50"
      )}
    >
      <button
        type="button"
        onClick={onOpen}
        aria-label={`Open ${row.title}`}
        title="Open preview"
        className={cn(
          "m-2 mb-0 flex flex-1 flex-col items-center justify-center gap-1.5 rounded-md",
          kind.tone
        )}
      >
        <kind.icon size={40} strokeWidth={1.4} aria-hidden="true" />
        {ext && (
          <span className="font-mono text-[11px] font-semibold tracking-[0.06em] uppercase">
            {ext}
          </span>
        )}
      </button>
      <div className="px-3.5 pt-2 pb-3" title={row.fileName ?? row.title}>
        <p className="flex items-center gap-1.5 text-[13.5px] font-semibold">
          {row.restricted && (
            <Lock
              size={12}
              className="shrink-0 text-muted-foreground"
              aria-label="Restricted"
            />
          )}
          <span className="truncate">{row.title}</span>
          <PinnedIndicator documentId={row.documentId} />
        </p>
        <p className="mt-0.5 truncate text-[12px] text-muted-foreground">
          <FriendlyDate iso={row.createdAt} />
          {row.sizeBytes !== null && ` · ${formatBytes(row.sizeBytes)}`}
        </p>
      </div>
      {onToggle && (
        <input
          type="checkbox"
          aria-label={`Select ${row.title}`}
          checked={checked}
          onChange={onToggle}
          className={cn(
            "absolute top-3.5 left-3.5 size-4",
            checked
              ? "opacity-100"
              : "opacity-0 group-focus-within:opacity-100 group-hover:opacity-100"
          )}
        />
      )}
      <ItemMenu
        label={row.title}
        actions={actions}
        className="absolute top-3 right-3 rounded-md bg-card/90 opacity-0 group-focus-within:opacity-100 group-hover:opacity-100"
      />
    </li>
  )
}

const TH =
  "px-3 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase"

/** List view: folders first, then documents, in one table. */
export function BrowserList({
  folders,
  rows,
  checked,
  onToggle,
  onToggleAll,
  onOpenFolder,
  onOpenDocument,
  folderActions,
  documentActions,
  empty,
  selectable = true,
  folderDrag,
  folderDrop,
  documentDrag,
}: {
  folders: FolderNode[]
  rows: DocumentListItemDto[]
  checked: Set<string>
  onToggle: (documentId: string) => void
  onToggleAll: (on: boolean) => void
  onOpenFolder: (folder: FolderNode) => void
  onOpenDocument: (row: DocumentListItemDto) => void
  folderActions: (folder: FolderNode) => ItemAction[]
  documentActions: (row: DocumentListItemDto) => ItemAction[]
  empty: ReactNode
  /** false for a listing without checkboxes. */
  selectable?: boolean
  /** Drag and drop, as on the tiles - see FolderTile/DocumentTile. */
  folderDrag?: (folder: FolderNode) => DragBinding | undefined
  folderDrop?: (folder: FolderNode) => DropBinding | undefined
  documentDrag?: (row: DocumentListItemDto) => DragBinding | undefined
}) {
  const allChecked =
    rows.length > 0 && rows.every((r) => checked.has(r.documentId))
  // Checkboxes appear on hover/focus; once anything is selected they all stay visible.
  const selecting = checked.size > 0
  const hoverOnly = (show: boolean) =>
    cn(
      "transition-opacity",
      show
        ? "opacity-100"
        : "opacity-0 group-focus-within:opacity-100 group-hover:opacity-100 focus-visible:opacity-100"
    )
  return (
    <div className="overflow-x-auto rounded-lg border border-border bg-card shadow-[var(--ledger-shadow)]">
      <table className="w-full min-w-[680px] border-collapse">
        <thead>
          <tr className="group border-b border-border">
            <th className={`${TH} w-10`}>
              {selectable && rows.length > 0 && (
                <input
                  type="checkbox"
                  aria-label="Select all documents on this page"
                  checked={allChecked}
                  className={hoverOnly(selecting)}
                  onChange={(e) => onToggleAll(e.target.checked)}
                />
              )}
            </th>
            <th className={TH}>Name</th>
            <th className={TH}>Modified</th>
            <th className={TH}>Type</th>
            <th className={TH}>Size</th>
            <th className={`${TH} w-12`}>
              <span className="sr-only">Actions</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {folders.length === 0 && rows.length === 0 && (
            <tr>
              <td
                colSpan={6}
                className="px-3 py-10 text-center text-[13px] text-muted-foreground"
              >
                {empty}
              </td>
            </tr>
          )}
          {folders.map((folder) => {
            const drop = folderDrop?.(folder)
            return (
              <tr
                key={folder.folderId}
                {...folderDrag?.(folder)}
                {...drop?.handlers}
                className={cn(
                  "border-b border-border last:border-b-0",
                  drop?.over
                    ? "bg-primary/10 outline-2 -outline-offset-2 outline-primary"
                    : "hover:bg-muted/40"
                )}
              >
                <td className="px-3 py-2" />
                <td className="px-3 py-2">
                  <button
                    type="button"
                    onClick={() => onOpenFolder(folder)}
                    className="flex items-center gap-2.5 text-left"
                  >
                    <FolderGlyph size={24} />
                    <span className="text-[13.5px] font-semibold hover:underline">
                      {folder.name}
                    </span>
                    {folder.restricted && (
                      <Lock
                        size={13}
                        className="text-muted-foreground"
                        aria-label="Restricted"
                      />
                    )}
                  </button>
                </td>
                <td className="px-3 py-2 text-[13px] whitespace-nowrap text-muted-foreground">
                  <FriendlyDate iso={folder.updatedAt ?? folder.createdAt} />
                </td>
                <td className="px-3 py-2 text-[13px] text-muted-foreground">
                  Folder
                </td>
                <td className="px-3 py-2 text-[12.5px] text-muted-foreground">
                  {itemsLabel(itemCount(folder))}
                </td>
                <td className="px-3 py-2">
                  <ItemMenu
                    label={folder.name}
                    actions={folderActions(folder)}
                  />
                </td>
              </tr>
            )
          })}
          {rows.map((row) => {
            const kind = fileKind(row.fileName)
            return (
              <tr
                key={row.documentId}
                {...documentDrag?.(row)}
                className={cn(
                  "group border-b border-border last:border-b-0",
                  checked.has(row.documentId)
                    ? "bg-primary/5"
                    : "hover:bg-muted/40"
                )}
              >
                <td className="px-3 py-2">
                  {selectable && (
                    <input
                      type="checkbox"
                      aria-label={`Select ${row.title}`}
                      checked={checked.has(row.documentId)}
                      onChange={() => onToggle(row.documentId)}
                      className={hoverOnly(selecting)}
                    />
                  )}
                </td>
                <td className="px-3 py-2">
                  <div className="flex items-center gap-2.5">
                    <span
                      className={cn(
                        "flex size-7 shrink-0 items-center justify-center rounded",
                        kind.tone
                      )}
                    >
                      <kind.icon size={16} aria-hidden="true" />
                    </span>
                    <div className="min-w-0">
                      <p className="flex items-center gap-1.5 text-[13.5px] font-semibold">
                        <button
                          type="button"
                          onClick={() => onOpenDocument(row)}
                          className="truncate text-left hover:underline"
                        >
                          {row.title}
                        </button>
                        {row.restricted && (
                          <Lock
                            size={12}
                            className="shrink-0 text-muted-foreground"
                            aria-label="Restricted"
                          />
                        )}
                        <PinnedIndicator documentId={row.documentId} />
                      </p>
                      {row.fileName && (
                        <p className="truncate font-mono text-[11.5px] text-muted-foreground">
                          {row.fileName}
                        </p>
                      )}
                    </div>
                  </div>
                </td>
                <td className="px-3 py-2 text-[13px] whitespace-nowrap text-muted-foreground">
                  <FriendlyDate iso={row.createdAt} />
                </td>
                <td className="px-3 py-2 text-[13px]">
                  {row.documentTypeName ?? kind.label}
                </td>
                <td className="px-3 py-2 font-mono text-[12.5px] text-muted-foreground">
                  {row.sizeBytes !== null ? formatBytes(row.sizeBytes) : "—"}
                </td>
                <td className="px-3 py-2">
                  <ItemMenu label={row.title} actions={documentActions(row)} />
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
