"use client"

import { useEffect, useRef, useState } from "react"
import { usePathname } from "next/navigation"

/**
 * Thin progress bar at the top of the screen. Next.js keeps the old page on screen until the next route's
 * first response arrives, so on a slow connection a click on a link looks like nothing happened. This starts
 * the bar on the click of an internal link to another page and completes it when the pathname changes.
 */
export function NavProgress() {
  const pathname = usePathname()
  const [width, setWidth] = useState(0)
  const [visible, setVisible] = useState(false)
  const timers = useRef<{ tick?: number; safety?: number; hide?: number }>({})
  const active = useRef(false)

  function clear() {
    window.clearInterval(timers.current.tick)
    window.clearTimeout(timers.current.safety)
  }

  function finish() {
    if (!active.current) return
    active.current = false
    clear()
    setWidth(100)
    timers.current.hide = window.setTimeout(() => {
      setVisible(false)
      setWidth(0)
    }, 260)
  }

  function start() {
    if (active.current) return
    active.current = true
    window.clearTimeout(timers.current.hide)
    setVisible(true)
    setWidth(8)
    // Ease towards 90% and never reach it: the real end is the pathname change.
    timers.current.tick = window.setInterval(() => {
      setWidth((w) => w + (90 - w) * 0.12)
    }, 200)
    timers.current.safety = window.setTimeout(finish, 20000)
  }

  useEffect(() => {
    function onClick(e: MouseEvent) {
      if (e.defaultPrevented || e.button !== 0) return
      if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return
      const a = (e.target as Element | null)?.closest?.("a")
      if (!a || a.target === "_blank" || a.hasAttribute("download")) return
      const href = a.getAttribute("href")
      if (!href || href.startsWith("#")) return
      let url: URL
      try {
        url = new URL(a.href, window.location.href)
      } catch {
        return
      }
      if (url.origin !== window.location.origin) return
      if (url.pathname === window.location.pathname) return
      start()
    }
    // Capture phase: Next's <Link> calls preventDefault() while handling the click, so a bubble listener would
    // see defaultPrevented and skip every client-side navigation.
    document.addEventListener("click", onClick, true)
    return () => {
      document.removeEventListener("click", onClick, true)
      clear()
      window.clearTimeout(timers.current.hide)
    }
    // start/finish only touch refs and setState
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    finish()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pathname])

  return (
    <div
      role="progressbar"
      aria-hidden={!visible}
      aria-label="Loading page"
      className="pointer-events-none fixed inset-x-0 top-0 z-[100] h-[3px]"
      style={{ opacity: visible ? 1 : 0, transition: "opacity 0.2s ease" }}
    >
      <div
        className="h-full rounded-r-full bg-primary"
        style={{
          width: `${width}%`,
          transition: "width 0.2s ease-out",
          boxShadow:
            "0 0 8px color-mix(in srgb, var(--primary) 55%, transparent)",
        }}
      />
    </div>
  )
}
