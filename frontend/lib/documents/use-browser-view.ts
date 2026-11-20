"use client"

import { useSyncExternalStore, type CSSProperties } from "react"

export type BrowserView = "grid" | "list"

// Grid or list, remembered per browser for every document listing (My
// Documents, Shared with me). Falls back to memory when storage is blocked.
const VIEW_KEY = "dms.documents.view"
const listeners = new Set<() => void>()
let memoryView: BrowserView = "grid"

function readView(): BrowserView {
  try {
    const stored = localStorage.getItem(VIEW_KEY)
    if (stored === "grid" || stored === "list") return stored
  } catch {}
  return memoryView
}

export function setBrowserView(view: BrowserView) {
  memoryView = view
  try {
    localStorage.setItem(VIEW_KEY, view)
  } catch {}
  listeners.forEach((listener) => listener())
}

export function useBrowserView(): BrowserView {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    readView,
    () => "grid"
  )
}

/**
 * How big the tiles are in the grid view - and so how many fit in a row. Remembered per browser, like the view, and
 * shared by every listing that has a grid (Documents, Shared with me, Pinned, Bookmarks).
 */
export type TileSize = "xsmall" | "small" | "medium" | "large"

export const TILE_SIZES: { value: TileSize; label: string }[] = [
  { value: "xsmall", label: "Extra small tiles" },
  { value: "small", label: "Small tiles" },
  { value: "medium", label: "Medium tiles" },
  { value: "large", label: "Large tiles" },
]

const TILE_SIZE_KEY = "dms.documents.tileSize"
const DEFAULT_TILE_SIZE: TileSize = "small"
let memoryTileSize: TileSize = DEFAULT_TILE_SIZE

function readTileSize(): TileSize {
  try {
    const stored = localStorage.getItem(TILE_SIZE_KEY)
    if (
      stored === "xsmall" ||
      stored === "small" ||
      stored === "medium" ||
      stored === "large"
    )
      return stored
  } catch {}
  return memoryTileSize
}

export function setTileSize(size: TileSize) {
  memoryTileSize = size
  try {
    localStorage.setItem(TILE_SIZE_KEY, size)
  } catch {}
  listeners.forEach((listener) => listener())
}

export function useTileSize(): TileSize {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    readTileSize,
    () => DEFAULT_TILE_SIZE
  )
}

// Extra small is about 10 across on a wide screen, small (the original) about 8, medium about 6 and large about 4. The tile height
// follows through --tile-h (read by the tiles in components/documents/browser-items.tsx). The classes are written out
// in full so Tailwind finds them.
const TILE_GRIDS: Record<TileSize, { className: string; height: number }> = {
  xsmall: {
    className: "grid grid-cols-[repeat(auto-fill,minmax(140px,1fr))] gap-2.5",
    height: 178,
  },
  small: {
    className: "grid grid-cols-[repeat(auto-fill,minmax(168px,1fr))] gap-3",
    height: 196,
  },
  medium: {
    className: "grid grid-cols-[repeat(auto-fill,minmax(250px,1fr))] gap-4",
    height: 244,
  },
  large: {
    className: "grid grid-cols-[repeat(auto-fill,minmax(340px,1fr))] gap-5",
    height: 300,
  },
}

/** Spread onto the grid's `<ul>`: its column layout for the chosen tile size, and the matching tile height. */
export function tileGridProps(size: TileSize): {
  className: string
  style: CSSProperties
} {
  const grid = TILE_GRIDS[size]
  return {
    className: grid.className,
    style: { ["--tile-h" as string]: `${grid.height}px` },
  }
}
