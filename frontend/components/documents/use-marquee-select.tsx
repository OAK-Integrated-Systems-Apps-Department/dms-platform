"use client"

import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type PointerEvent as ReactPointerEvent,
} from "react"
import { createPortal } from "react-dom"

/** Every selectable tile or row carries this, e.g. `doc:<id>` or `folder:<id>`. */
export const SELECT_KEY_ATTRIBUTE = "data-select-key"

/** Less than this many pixels of movement is a click, not a drag. */
const DRAG_THRESHOLD = 4

/** Anything the pointer can act on by itself: pressing on it never starts a selection box. */
const INTERACTIVE =
  "button, a, input, select, textarea, label, [role='menu'], [role='menuitem'], [data-no-marquee]"

interface Box {
  left: number
  top: number
  width: number
  height: number
}

interface Options {
  /** false switches the whole thing off (e.g. a listing without selection). */
  enabled: boolean
  /** A drag began. `additive` is true with Ctrl, Cmd or Shift held: the box then adds to what is already selected. */
  onStart: (additive: boolean) => void
  /** The keys of every tile or row the box touches right now. */
  onSelect: (keys: Set<string>) => void
  /** A plain click on empty space (no drag, no modifier). */
  onClickEmpty: () => void
}

/**
 * Drag a box over empty space to select every tile or row it touches, as in a file manager. Put `containerProps` on the
 * element that wraps the items (it must also cover some empty space to start from) and `data-select-key` on each item.
 * Starting on an item or a control never begins a box - items keep their own click and drag-to-move behaviour - and
 * touch input is left to scroll the page. The box is measured against the container, so it stays right while the page
 * scrolls under it.
 */
export function useMarqueeSelect({
  enabled,
  onStart,
  onSelect,
  onClickEmpty,
}: Options) {
  const containerRef = useRef<HTMLDivElement | null>(null)
  const [box, setBox] = useState<Box | null>(null)
  const cleanup = useRef<(() => void) | null>(null)

  // Never leave listeners or the no-select style behind, even when the list goes away mid-drag.
  useEffect(() => () => cleanup.current?.(), [])

  const onPointerDown = useCallback(
    (event: ReactPointerEvent<HTMLDivElement>) => {
      const container = containerRef.current
      if (!enabled || !container) return
      if (event.button !== 0 || event.pointerType === "touch") return
      const target = event.target as Element
      if (
        target.closest(`[${SELECT_KEY_ATTRIBUTE}]`) ||
        target.closest(INTERACTIVE)
      )
        return

      const additive = event.ctrlKey || event.metaKey || event.shiftKey
      const origin = container.getBoundingClientRect()
      // Where the drag began, relative to the container's own corner, so scrolling cannot move it.
      const startX = event.clientX - origin.left
      const startY = event.clientY - origin.top
      let lastX = event.clientX
      let lastY = event.clientY
      let dragging = false

      function measure() {
        const rect = container!.getBoundingClientRect()
        const x1 = rect.left + startX
        const y1 = rect.top + startY
        const next: Box = {
          left: Math.min(x1, lastX),
          top: Math.min(y1, lastY),
          width: Math.abs(lastX - x1),
          height: Math.abs(lastY - y1),
        }
        const keys = new Set<string>()
        container!
          .querySelectorAll<HTMLElement>(`[${SELECT_KEY_ATTRIBUTE}]`)
          .forEach((el) => {
            const r = el.getBoundingClientRect()
            const touches =
              r.left < next.left + next.width &&
              r.right > next.left &&
              r.top < next.top + next.height &&
              r.bottom > next.top
            const key = el.getAttribute(SELECT_KEY_ATTRIBUTE)
            if (touches && key) keys.add(key)
          })
        setBox(next)
        onSelect(keys)
      }

      function move(e: PointerEvent) {
        lastX = e.clientX
        lastY = e.clientY
        if (!dragging) {
          const moved = Math.hypot(
            e.clientX - (origin.left + startX),
            e.clientY - (origin.top + startY)
          )
          if (moved < DRAG_THRESHOLD) return
          dragging = true
          // No text gets highlighted underneath the box.
          document.body.style.userSelect = "none"
          onStart(additive)
        }
        measure()
      }

      function finish() {
        window.removeEventListener("pointermove", move)
        window.removeEventListener("pointerup", up)
        window.removeEventListener("pointercancel", finish)
        window.removeEventListener("scroll", measureOnScroll, true)
        document.body.style.userSelect = ""
        cleanup.current = null
        setBox(null)
      }

      function up() {
        if (!dragging && !additive) onClickEmpty()
        finish()
      }

      // The page scrolling under a held mouse moves the items but not the pointer: measure again.
      function measureOnScroll() {
        if (dragging) measure()
      }

      window.addEventListener("pointermove", move)
      window.addEventListener("pointerup", up)
      window.addEventListener("pointercancel", finish)
      window.addEventListener("scroll", measureOnScroll, true)
      cleanup.current = finish
    },
    [enabled, onStart, onSelect, onClickEmpty]
  )

  const overlay =
    box && typeof document !== "undefined"
      ? createPortal(
          <div
            aria-hidden="true"
            className="pointer-events-none fixed z-50 rounded-[3px] border border-primary bg-primary/15"
            style={{
              left: box.left,
              top: box.top,
              width: box.width,
              height: box.height,
            }}
          />,
          document.body
        )
      : null

  return {
    containerProps: { ref: containerRef, onPointerDown },
    overlay,
  }
}
