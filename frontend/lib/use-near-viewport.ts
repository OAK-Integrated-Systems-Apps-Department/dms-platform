"use client"

import { useEffect, useRef, useState } from "react"

/**
 * True once the element has come near the viewport - and stays true, so what was loaded for it is kept. For things
 * worth fetching only when they are about to be seen (a page picture, a thumbnail in a long list).
 */
export function useNearViewport<T extends HTMLElement>(rootMargin = "600px") {
  const ref = useRef<T>(null)
  // No IntersectionObserver (an old browser, a test environment): treated as near, so it just loads.
  const [near, setNear] = useState(() => typeof IntersectionObserver === "undefined")
  useEffect(() => {
    const element = ref.current
    if (!element || near) return
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) setNear(true)
      },
      { rootMargin }
    )
    observer.observe(element)
    return () => observer.disconnect()
  }, [near, rootMargin])
  return [ref, near] as const
}
