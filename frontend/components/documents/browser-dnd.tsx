"use client"

import { useEffect, useState, type DragEvent } from "react"
import { FolderInput, Upload } from "lucide-react"

import type { DocumentListItemDto } from "@/lib/documents/api"
import {
  isFileDrag,
  readDrop,
  type DroppedFile,
} from "@/lib/documents/dropped-files"
import type { FolderNode } from "@/lib/folders/api"
import { cn } from "@/lib/utils"

/** What's being dragged around inside My Documents. */
export interface DragItems {
  documents: DocumentListItemDto[]
  folders: FolderNode[]
}

/** Set on internal drags so they're never mistaken for text or links elsewhere. */
const ITEMS_TYPE = "application/x-oakdms-items"
/** The open folder's own background - a drop there means "here". */
const HERE = "here"
const TOP = "top"

type Handlers = {
  onDragOver: (e: DragEvent<HTMLElement>) => void
  onDragLeave: (e: DragEvent<HTMLElement>) => void
  onDrop: (e: DragEvent<HTMLElement>) => void
}

/** Spread `handlers` onto a folder tile/row/path segment; `over` lights it up. */
export interface DropBinding {
  over: boolean
  handlers: Handlers
}

/** Spread onto a tile or row to make it draggable. */
export interface DragBinding {
  draggable: true
  onDragStart: (e: DragEvent<HTMLElement>) => void
  onDragEnd: () => void
}

interface Over {
  key: string
  name: string
}

export function countLabel(items: DragItems) {
  const n = items.documents.length + items.folders.length
  if (items.folders.length === 0)
    return n === 1 ? `"${items.documents[0].title}"` : `${n} documents`
  if (items.documents.length === 0)
    return n === 1 ? `"${items.folders[0].name}"` : `${n} folders`
  return `${n} items`
}

/**
 * Google Drive style drag and drop for the folder view:
 * - files and folders dragged in from the computer upload into the open
 *   folder, or into the folder they're dropped on;
 * - documents and folders dragged onto a folder (or a folder in the path)
 *   move there.
 * Who may do what is decided by the caller (`canUploadTo` / `canMoveInto`);
 * a target that refuses shows the "not allowed" cursor.
 */
export function useBrowserDragDrop({
  here,
  canUploadTo,
  canMoveInto,
  onUpload,
  onMove,
}: {
  /** The open folder; null at the top level. */
  here: FolderNode | null
  canUploadTo: (target: FolderNode | null) => boolean
  canMoveInto: (items: DragItems, target: FolderNode | null) => boolean
  onUpload: (files: Promise<DroppedFile[]>, target: FolderNode | null) => void
  onMove: (items: DragItems, target: FolderNode | null) => void
}) {
  const [moving, setMoving] = useState<DragItems | null>(null)
  const [over, setOver] = useState<Over | null>(null)

  // A file let go anywhere else on the page must not make the browser open it.
  useEffect(() => {
    function guard(e: globalThis.DragEvent) {
      if (e.defaultPrevented || !isFileDrag(e.dataTransfer)) return
      e.preventDefault()
      if (e.type === "dragover" && e.dataTransfer)
        e.dataTransfer.dropEffect = "none"
      if (e.type === "drop") setOver(null)
    }
    function reset() {
      setOver(null)
    }
    window.addEventListener("dragover", guard)
    window.addEventListener("drop", guard)
    window.addEventListener("dragend", reset)
    return () => {
      window.removeEventListener("dragover", guard)
      window.removeEventListener("drop", guard)
      window.removeEventListener("dragend", reset)
    }
  }, [])

  function bind(
    key: string,
    target: FolderNode | null,
    filesOnly: boolean
  ): DropBinding {
    const name = target?.name ?? "My Documents"
    const accepts = (e: DragEvent<HTMLElement>) =>
      moving
        ? !filesOnly && canMoveInto(moving, target)
        : isFileDrag(e.dataTransfer) && canUploadTo(target)
    // Dialogs and side panels are portals: their events bubble up the React
    // tree to here, but they aren't part of this drop area.
    const outside = (e: DragEvent<HTMLElement>) =>
      !e.currentTarget.contains(e.target as Node)
    return {
      over: over?.key === key,
      handlers: {
        onDragOver(e) {
          if (outside(e) || (!moving && !isFileDrag(e.dataTransfer))) return
          e.stopPropagation()
          e.preventDefault()
          if (accepts(e)) {
            e.dataTransfer.dropEffect = moving ? "move" : "copy"
            setOver((o) => (o?.key === key ? o : { key, name }))
          } else {
            e.dataTransfer.dropEffect = "none"
            setOver((o) => (o?.key === key ? null : o))
          }
        },
        onDragLeave(e) {
          if (e.currentTarget.contains(e.relatedTarget as Node | null)) return
          setOver((o) => (o?.key === key ? null : o))
        },
        onDrop(e) {
          if (outside(e)) return
          e.preventDefault()
          e.stopPropagation()
          setOver(null)
          if (!accepts(e)) return
          if (moving) {
            const items = moving
            setMoving(null)
            onMove(items, target)
          } else {
            // Read now - the browser clears the drop once this handler returns.
            onUpload(readDrop(e.dataTransfer), target)
          }
        },
      },
    }
  }

  return {
    /** The open folder's whole area - files dropped anywhere on it upload here. */
    area: bind(HERE, here, true),
    /** A folder tile/row, or a folder (null = My Documents) in the path. */
    target: (folder: FolderNode | null) =>
      bind(folder?.folderId ?? TOP, folder, false),
    /** Makes these items draggable; undefined when there's nothing the user may move. */
    source(items: DragItems): DragBinding | undefined {
      if (items.documents.length + items.folders.length === 0) return undefined
      return {
        draggable: true,
        onDragStart(e) {
          e.stopPropagation()
          e.dataTransfer.effectAllowed = "move"
          e.dataTransfer.setData(ITEMS_TYPE, "1")
          setDragImage(e, countLabel(items))
          setMoving(items)
        },
        onDragEnd() {
          setMoving(null)
          setOver(null)
        },
      }
    },
    hint: over ? { ...over, moving } : null,
  }
}

/** A small "Moving 3 documents" chip under the pointer instead of a ghost of the whole tile. */
function setDragImage(e: DragEvent<HTMLElement>, label: string) {
  const chip = document.createElement("div")
  chip.textContent = label.length > 48 ? `${label.slice(0, 47)}…` : label
  Object.assign(chip.style, {
    position: "fixed",
    top: "-1000px",
    left: "0",
    padding: "6px 12px",
    borderRadius: "8px",
    font: "600 13px system-ui, sans-serif",
    color: "#fff",
    background: "#1f6feb",
    boxShadow: "0 6px 18px rgba(0,0,0,.25)",
    whiteSpace: "nowrap",
  })
  document.body.appendChild(chip)
  e.dataTransfer.setDragImage(chip, -12, -12)
  setTimeout(() => chip.remove(), 0)
}

/** The bar at the bottom while something is held over a place it can go. */
export function DropHint({
  hint,
}: {
  hint: { name: string; moving: DragItems | null } | null
}) {
  if (!hint) return null
  const Icon = hint.moving ? FolderInput : Upload
  return (
    <div
      role="status"
      className={cn(
        "pointer-events-none fixed bottom-6 left-1/2 z-50 flex -translate-x-1/2 items-center gap-2 rounded-full",
        "bg-primary px-4 py-2 text-[13px] font-medium text-primary-foreground shadow-[0_8px_24px_rgba(0,0,0,0.25)]"
      )}
    >
      <Icon size={15} aria-hidden="true" />
      {hint.moving
        ? `Move ${countLabel(hint.moving)} to "${hint.name}"`
        : `Drop to upload to "${hint.name}"`}
    </div>
  )
}
