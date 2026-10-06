"use client"

import { useEffect, useRef, useState, useSyncExternalStore } from "react"

/**
 * The dashboard's motion helpers. All of them respect "reduce motion": cards
 * stop tilting and numbers jump straight to their value.
 */

function prefersReducedMotion(): boolean {
  return (
    typeof window !== "undefined" &&
    window.matchMedia("(prefers-reduced-motion: reduce)").matches
  )
}

/**
 * A card that tilts toward the pointer in 3D, with a light that follows it
 * (`.dash-tilt` / `.dash-glare` in dashboard.css). Only on devices that hover;
 * the styles are set straight on the element, so moving doesn't re-render.
 */
export function useTilt<T extends HTMLElement>(maxDegrees = 7) {
  const ref = useRef<T>(null)
  useEffect(() => {
    const element = ref.current
    if (!element || prefersReducedMotion()) return
    if (!window.matchMedia("(hover: hover)").matches) return
    let frame = 0
    function move(event: PointerEvent) {
      if (!element) return
      const box = element.getBoundingClientRect()
      const x = (event.clientX - box.left) / box.width
      const y = (event.clientY - box.top) / box.height
      cancelAnimationFrame(frame)
      frame = requestAnimationFrame(() => {
        element.style.setProperty(
          "--tilt-x",
          `${(0.5 - y) * maxDegrees * 2}deg`
        )
        element.style.setProperty(
          "--tilt-y",
          `${(x - 0.5) * maxDegrees * 2}deg`
        )
        element.style.setProperty("--glare-x", `${x * 100}%`)
        element.style.setProperty("--glare-y", `${y * 100}%`)
        element.dataset.tilting = "true"
      })
    }
    function leave() {
      if (!element) return
      cancelAnimationFrame(frame)
      element.style.setProperty("--tilt-x", "0deg")
      element.style.setProperty("--tilt-y", "0deg")
      element.dataset.tilting = "false"
    }
    element.addEventListener("pointermove", move)
    element.addEventListener("pointerleave", leave)
    return () => {
      cancelAnimationFrame(frame)
      element.removeEventListener("pointermove", move)
      element.removeEventListener("pointerleave", leave)
    }
  }, [maxDegrees])
  return ref
}

/**
 * A number that counts up (or down) to `target` with an ease-out, from
 * wherever it last was. `delay` holds it at its start for that long first.
 */
export function useCountUp(target: number, duration = 1200, delay = 0) {
  const [value, setValue] = useState(0)
  const current = useRef(0)
  useEffect(() => {
    const reduce = prefersReducedMotion()
    const origin = current.current
    let frame = 0
    // A hidden tab runs no animation frames - show the figure straight away instead of 0.
    if (document.hidden) {
      queueMicrotask(() => {
        current.current = target
        setValue(target)
      })
      return
    }
    let start = 0
    function step(now: number) {
      if (!start) start = now + (reduce ? 0 : delay)
      const t = reduce ? 1 : Math.min(1, Math.max(0, (now - start) / duration))
      const eased = 1 - Math.pow(1 - t, 3)
      current.current = origin + (target - origin) * eased
      setValue(current.current)
      if (t < 1) frame = requestAnimationFrame(step)
    }
    frame = requestAnimationFrame(step)
    return () => cancelAnimationFrame(frame)
  }, [target, duration, delay])
  return value
}

/** An element's size, kept up to date - a callback ref, so it works for elements that appear later. */
export function useBox<T extends HTMLElement>() {
  const [element, setElement] = useState<T | null>(null)
  const [box, setBox] = useState({ width: 0, height: 0 })
  useEffect(() => {
    if (!element) return
    const observer = new ResizeObserver(([entry]) =>
      setBox({
        width: entry.contentRect.width,
        height: entry.contentRect.height,
      })
    )
    observer.observe(element)
    return () => observer.disconnect()
  }, [element])
  return [setElement, box] as const
}

const noSubscription = () => () => {}

/**
 * The time on the user's own clock, to the minute - null while the page is
 * rendered on the server, so the greeting and date are never the server's.
 */
export function useNow(): Date | null {
  const minute = useSyncExternalStore(
    noSubscription,
    () => Math.floor(Date.now() / 60_000),
    () => null
  )
  return minute === null ? null : new Date(minute * 60_000)
}
