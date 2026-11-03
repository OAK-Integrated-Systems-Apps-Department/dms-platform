import { renderHook } from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

const refreshSession = vi.fn()
vi.mock("@/lib/api-fetch", () => ({ refreshSession: () => refreshSession() }))

import { beginSessionKeepAlive, useProactiveSessionRefresh } from "./use-proactive-session-refresh"

const MINUTE = 60_000

describe("useProactiveSessionRefresh", () => {
  beforeEach(() => {
    vi.useFakeTimers()
    refreshSession.mockReset()
  })
  afterEach(() => vi.useRealTimers())

  it("renews the session while the user is active", () => {
    renderHook(() => useProactiveSessionRefresh())
    window.dispatchEvent(new Event("keydown"))
    vi.advanceTimersByTime(5 * MINUTE)
    expect(refreshSession).toHaveBeenCalledTimes(1)
  })

  it("stops renewing once the user has been idle for over 10 minutes", () => {
    renderHook(() => useProactiveSessionRefresh())
    vi.advanceTimersByTime(15 * MINUTE)
    // Checks at 5 and 10 min still see recent activity (the hook's start); the one at 15 does not.
    expect(refreshSession).toHaveBeenCalledTimes(1)
  })

  it("keeps renewing with no input at all while long-running work (the editor) holds a keep-alive, and stops after", () => {
    renderHook(() => useProactiveSessionRefresh())
    const end = beginSessionKeepAlive()
    vi.advanceTimersByTime(30 * MINUTE)
    expect(refreshSession).toHaveBeenCalledTimes(6) // every 5 minutes, no activity needed

    end()
    refreshSession.mockClear()
    vi.advanceTimersByTime(30 * MINUTE)
    expect(refreshSession).not.toHaveBeenCalled()
  })

  it("counts scrolling inside an inner panel (scroll does not bubble, so it is listened for in the capture phase)", () => {
    renderHook(() => useProactiveSessionRefresh())
    vi.advanceTimersByTime(9 * MINUTE) // nearly idle
    const panel = document.createElement("div")
    document.body.appendChild(panel)
    panel.dispatchEvent(new Event("scroll")) // does not bubble
    vi.advanceTimersByTime(1 * MINUTE)
    // The check at 10 min (the start-of-page activity is now 10 min old) refreshes only because of the scroll.
    expect(refreshSession).toHaveBeenCalledTimes(2)
    panel.remove()
  })
})

describe("returning to the tab", () => {
  beforeEach(() => {
    vi.useFakeTimers()
    refreshSession.mockReset()
  })
  afterEach(() => vi.useRealTimers())

  function setVisibility(state: "visible" | "hidden") {
    Object.defineProperty(document, "visibilityState", { configurable: true, get: () => state })
    document.dispatchEvent(new Event("visibilitychange"))
  }

  it("renews the session straight away when the tab becomes visible after a while away", () => {
    renderHook(() => useProactiveSessionRefresh())
    setVisibility("hidden")
    vi.advanceTimersByTime(25 * MINUTE) // covered by another window: the timer skips hidden tabs
    expect(refreshSession).not.toHaveBeenCalled()

    setVisibility("visible")
    expect(refreshSession).toHaveBeenCalledTimes(1)
  })

  it("does not renew again if it was renewed moments ago", () => {
    renderHook(() => useProactiveSessionRefresh())
    vi.advanceTimersByTime(30_000)
    setVisibility("visible")
    expect(refreshSession).not.toHaveBeenCalled()
  })
})
