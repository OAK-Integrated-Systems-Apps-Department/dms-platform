import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { cancelIntentionalLogout, isIntentionalLogout, markIntentionalLogout } from "./api-fetch"

beforeEach(() => {
  vi.useFakeTimers()
  cancelIntentionalLogout()
})
afterEach(() => {
  cancelIntentionalLogout()
  vi.useRealTimers()
})

describe("the intentional-logout window", () => {
  it("is off until a logout marks it", () => {
    expect(isIntentionalLogout()).toBe(false)
  })

  it("lasts 15 seconds and then lapses on its own", () => {
    markIntentionalLogout()
    vi.advanceTimersByTime(14_999)
    expect(isIntentionalLogout()).toBe(true)
    vi.advanceTimersByTime(2)
    expect(isIntentionalLogout()).toBe(false)
  })

  it("can be ended at once (a failed logout), and stays ended", () => {
    markIntentionalLogout()
    cancelIntentionalLogout()
    expect(isIntentionalLogout()).toBe(false)
    vi.advanceTimersByTime(60_000)
    expect(isIntentionalLogout()).toBe(false)
  })

  it("a second mark restarts the window instead of the first timer ending it early", () => {
    markIntentionalLogout()
    vi.advanceTimersByTime(10_000)
    markIntentionalLogout()
    vi.advanceTimersByTime(10_000)
    expect(isIntentionalLogout()).toBe(true)
    vi.advanceTimersByTime(5_001)
    expect(isIntentionalLogout()).toBe(false)
  })

  it("cancelling when nothing is marked does nothing", () => {
    expect(() => cancelIntentionalLogout()).not.toThrow()
    expect(isIntentionalLogout()).toBe(false)
  })
})