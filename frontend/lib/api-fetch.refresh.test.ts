import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { API_BASE_URL } from "@/lib/api-config"
import { ApiError } from "@/lib/api-error"
import {
  TERMINAL_REFRESH_SUPPRESSION_MS,
  apiFetch,
  apiUpload,
  refreshSession,
  resetTerminalRefreshFailure,
} from "./api-fetch"

const REFRESH = `${API_BASE_URL}/api/v1/auth/refresh`
const LOGIN = `${API_BASE_URL}/api/v1/auth/login`
const PROTECTED = `${API_BASE_URL}/api/v1/things`

type RefreshAnswer = { status: number } | "network-error"
let refreshAnswer: RefreshAnswer
let sessionRenewed: boolean
let fetchMock: ReturnType<typeof vi.fn>

const respond = (status: number) => new Response(status === 204 ? null : JSON.stringify({ message: `s${status}` }), { status })
const calls = (url: string) => fetchMock.mock.calls.filter(([u]) => String(u) === url).length

beforeEach(() => {
  vi.useFakeTimers()
  resetTerminalRefreshFailure()
  refreshAnswer = { status: 401 }
  sessionRenewed = false
  fetchMock = vi.fn(async (url: string) => {
    if (String(url) === REFRESH) {
      if (refreshAnswer === "network-error") throw new TypeError("Failed to fetch")
      if (refreshAnswer.status === 200) sessionRenewed = true
      return respond(refreshAnswer.status)
    }
    if (String(url) === LOGIN) return respond(200)
    if (String(url).endsWith("/mfa-required")) return respond(200)
    return sessionRenewed ? respond(200) : respond(401)
  })
  vi.stubGlobal("fetch", fetchMock)
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  resetTerminalRefreshFailure()
})

const batch = (n: number) => Promise.allSettled(Array.from({ length: n }, () => apiFetch(PROTECTED)))
const rejectedWith401 = (results: PromiseSettledResult<Response>[]) =>
  results.every((r) => r.status === "rejected" && r.reason instanceof ApiError && r.reason.status === 401)

describe("refresh - a valid session is unaffected", () => {
  it("a 401 is refreshed once and the request retried", async () => {
    refreshAnswer = { status: 200 }

    const response = await apiFetch(PROTECTED)

    expect(response.status).toBe(200)
    expect(calls(REFRESH)).toBe(1)
    expect(calls(PROTECTED)).toBe(2)
  })

  it("keeps renewing on every later 401 while refreshes succeed", async () => {
    refreshAnswer = { status: 200 }
    await apiFetch(PROTECTED)
    sessionRenewed = false
    await apiFetch(PROTECTED)

    expect(calls(REFRESH)).toBe(2)
  })

  it("concurrent 401s share one refresh (the single-flight guard is kept)", async () => {
    refreshAnswer = { status: 200 }

    const results = await batch(6)

    expect(results.every((r) => r.status === "fulfilled")).toBe(true)
    expect(calls(REFRESH)).toBe(1)
  })
})

describe("refresh - a terminal failure (HTTP 401 from /auth/refresh)", () => {
  it("fails the request with the original 401 and makes no retry", async () => {
    const [result] = await batch(1)

    expect(rejectedWith401([result])).toBe(true)
    expect(calls(REFRESH)).toBe(1)
    expect(calls(PROTECTED)).toBe(1)
  })

  it("concurrent requests share the decision: one refresh, every request fails with its 401", async () => {
    const results = await batch(7)

    expect(rejectedWith401(results)).toBe(true)
    expect(calls(REFRESH)).toBe(1)
  })

  it("later failing batches do NOT call /auth/refresh again inside the window", async () => {
    await batch(7)
    vi.advanceTimersByTime(1_000)
    const second = await batch(7)
    vi.advanceTimersByTime(5_000)
    const third = await batch(7)
    vi.advanceTimersByTime(10_000)
    const fourth = await batch(7)

    expect(rejectedWith401(second) && rejectedWith401(third) && rejectedWith401(fourth)).toBe(true)
    expect(calls(REFRESH)).toBe(1) // 28 failing requests, one refresh
    expect(calls(PROTECTED)).toBe(28)
  })

  it("the window is bounded: it lapses after TERMINAL_REFRESH_SUPPRESSION_MS and the next failure tries again", async () => {
    await batch(1)
    vi.advanceTimersByTime(TERMINAL_REFRESH_SUPPRESSION_MS - 1)
    await batch(1)
    expect(calls(REFRESH)).toBe(1)

    vi.advanceTimersByTime(2)
    await batch(1)
    expect(calls(REFRESH)).toBe(2)
  })

  it("a refresh that succeeds after the window clears the memory completely", async () => {
    await batch(1)
    vi.advanceTimersByTime(TERMINAL_REFRESH_SUPPRESSION_MS + 1)
    refreshAnswer = { status: 200 }
    await apiFetch(PROTECTED)
    expect(calls(REFRESH)).toBe(2)

    sessionRenewed = false
    refreshAnswer = { status: 401 }
    await batch(1) // 401 again: a fresh attempt is made, not suppressed
    expect(calls(REFRESH)).toBe(3)
  })

  it("is still bounded when nobody ever signs in: a poll every 15s refreshes at most about every 30s", async () => {
    for (let elapsed = 0; elapsed <= 120_000; elapsed += 15_000) {
      await batch(1)
      vi.advanceTimersByTime(15_000)
    }

    expect(calls(PROTECTED)).toBe(9)
    expect(calls(REFRESH)).toBeLessThanOrEqual(5)
    expect(calls(REFRESH)).toBeGreaterThanOrEqual(3)
  })

  it("refreshSession itself answers false without a request while suppressed (proactive refresh stays quiet)", async () => {
    await batch(1)
    fetchMock.mockClear()

    await expect(refreshSession()).resolves.toBe(false)
    await expect(refreshSession()).resolves.toBe(false)

    expect(fetchMock).not.toHaveBeenCalled()
  })

  it("a successful sign-in ends the suppression at once", async () => {
    await batch(1)
    expect(calls(REFRESH)).toBe(1)

    await apiFetch(LOGIN, { method: "POST" })
    refreshAnswer = { status: 200 }
    const response = await apiFetch(PROTECTED)

    expect(response.status).toBe(200)
    expect(calls(REFRESH)).toBe(2)
  })

  it("a failed sign-in does not", async () => {
    await batch(1)
    fetchMock.mockImplementationOnce(async () => respond(401))
    await expect(apiFetch(LOGIN, { method: "POST" })).rejects.toBeInstanceOf(ApiError)

    await batch(3)

    expect(calls(REFRESH)).toBe(1)
  })

  it("a different successful call does not reset it", async () => {
    await batch(1)
    await apiFetch(LOGIN.replace("login", "mfa-required"))
    await batch(1)

    expect(calls(REFRESH)).toBe(1)
  })
})

describe("refresh - failures that say nothing about the session are not terminal", () => {
  it("a network failure of the refresh call: the next failing request refreshes again", async () => {
    refreshAnswer = "network-error"
    await batch(1)
    await batch(1)

    expect(calls(REFRESH)).toBe(2)
  })

  it("an HTTP 500 from the refresh call: the next failing request refreshes again", async () => {
    refreshAnswer = { status: 500 }
    await batch(1)
    await batch(1)

    expect(calls(REFRESH)).toBe(2)
  })

  it("an HTTP 503 and a 403 are not terminal either", async () => {
    refreshAnswer = { status: 503 }
    await batch(1)
    refreshAnswer = { status: 403 }
    await batch(1)
    await batch(1)

    expect(calls(REFRESH)).toBe(3)
  })

  it("recovers: after a 500 the next refresh may succeed", async () => {
    refreshAnswer = { status: 500 }
    await batch(1)
    refreshAnswer = { status: 200 }
    const response = await apiFetch(PROTECTED)

    expect(response.status).toBe(200)
  })
})

describe("refresh - no recursion", () => {
  it("a 401 from the refresh endpoint itself, called through apiFetch, is not refreshed", async () => {
    await expect(apiFetch(REFRESH, { method: "POST" })).rejects.toBeInstanceOf(ApiError)

    expect(calls(REFRESH)).toBe(1)
  })

  it("the refresh request is made once per refresh, never from inside itself", async () => {
    await batch(4)

    expect(fetchMock.mock.calls.filter(([u]) => String(u) === REFRESH)).toHaveLength(1)
  })
})

describe("refresh - uploads share the same behaviour", () => {
  class FakeXhr {
    static statuses: number[] = []
    static sent = 0
    status = 0
    responseText = ""
    withCredentials = false
    upload: Record<string, unknown> = {}
    onload: (() => void) | null = null
    onerror: (() => void) | null = null
    onabort: (() => void) | null = null
    open() {}
    setRequestHeader() {}
    addEventListener() {}
    abort() {}
    send() {
      FakeXhr.sent++
      this.status = FakeXhr.statuses.shift() ?? 401
      queueMicrotask(() => this.onload?.())
    }
  }

  beforeEach(() => {
    FakeXhr.statuses = []
    FakeXhr.sent = 0
    vi.stubGlobal("XMLHttpRequest", FakeXhr)
  })

  it("a valid session: one refresh, the upload is retried and succeeds", async () => {
    refreshAnswer = { status: 200 }
    FakeXhr.statuses = [401, 200]

    await expect(apiUpload(PROTECTED, new FormData())).resolves.toBeNull()

    expect(calls(REFRESH)).toBe(1)
    expect(FakeXhr.sent).toBe(2)
  })

  it("a terminal failure: the upload fails with 401, and a second upload does not call refresh again", async () => {
    FakeXhr.statuses = [401, 401]

    await expect(apiUpload(PROTECTED, new FormData())).rejects.toMatchObject({ status: 401 })
    await expect(apiUpload(PROTECTED, new FormData())).rejects.toMatchObject({ status: 401 })

    expect(calls(REFRESH)).toBe(1)
    expect(FakeXhr.sent).toBe(2)
  })
})