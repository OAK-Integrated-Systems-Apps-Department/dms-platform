"use client"

import { useEffect, useRef, useState, useSyncExternalStore } from "react"
import { createPortal } from "react-dom"

// Portals need the document, which the server does not have: false while rendering on the server, true in the browser.
const subscribeToNothing = () => () => {}

// The check often ends in well under a second; the animation stays up at least this long so it can actually be seen.
const MIN_VISIBLE_MS = 2500

/**
 * The growing-sprout animation (public/images/loading.gif) in the middle of the page over a blurred screen (no box), with a short message under it, while
 * something is being checked (for duplicates, for viruses). It is a status, not a blocker: it ignores the pointer, so
 * whatever is underneath stays usable, and it sits above sheets and dialogs so it is seen wherever it is shown.
 */
export function CenteredLoader({
  message,
  active,
}: {
  message: string
  active: boolean
}) {
  const inBrowser = useSyncExternalStore(
    subscribeToNothing,
    () => true,
    () => false
  )
  const [visible, setVisible] = useState(false)
  const [wasActive, setWasActive] = useState(false)
  const shownAt = useRef(0)
  // Turning on is derived while rendering; turning off waits out the minimum time in the effect below.
  if (active !== wasActive) {
    setWasActive(active)
    if (active) setVisible(true)
  }
  useEffect(() => {
    if (active) {
      shownAt.current = Date.now()
      return
    }
    const left = Math.max(0, MIN_VISIBLE_MS - (Date.now() - shownAt.current))
    const timer = setTimeout(() => setVisible(false), left)
    return () => clearTimeout(timer)
  }, [active])
  if (!inBrowser || !visible) return null
  return createPortal(
    <div
      role="status"
      aria-live="polite"
      // Starts where the sidebar ends: the sidebar stays sharp, only the page content is blurred.
      ref={(el) => {
        if (!el) return
        const sidebar = document.querySelector('[data-slot="sidebar-container"]')
        el.style.left = `${Math.max(0, sidebar?.getBoundingClientRect().right ?? 0)}px`
      }}
      className="pointer-events-none fixed inset-y-0 right-0 z-[70] flex flex-col items-center justify-center gap-2 bg-background/30 backdrop-blur-[3px] animate-in fade-in"
    >
      {/* An animated GIF: next/image would not animate it, and the file is small and local. */}
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img
        src="/images/loading.gif"
        alt=""
        width={260}
        height={252}
        className="h-[252px] w-[260px]"
      />
      <p className="text-[15px] font-medium text-foreground">{message}</p>
    </div>,
    document.body
  )
}
