"use client"

import { useSyncExternalStore } from "react"

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
