import { QueryClient, QueryClientProvider, useQueries } from "@tanstack/react-query"
import { renderHook } from "@testing-library/react"
import type { ReactNode } from "react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { API_BASE_URL } from "@/lib/api-config"
import { apiFetchJson, resetTerminalRefreshFailure } from "./api-fetch"

const REFRESH = `${API_BASE_URL}/api/v1/auth/refresh`
const PROTECTED = `${API_BASE_URL}/api/v1/things`

let fetchMock: ReturnType<typeof vi.fn>
const calls = (url: string) => fetchMock.mock.calls.filter(([u]) => String(u) === url).length

beforeEach(() => {
  vi.useFakeTimers()
  resetTerminalRefreshFailure()
  fetchMock = vi.fn(async () => new Response(JSON.stringify({ message: "no" }), { status: 401 }))
  vi.stubGlobal("fetch", fetchMock)
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  resetTerminalRefreshFailure()
})

function mount(queries: number, options: { retry?: number | false } = {}) {
  // The app's own QueryClient (components/providers/providers.tsx) sets no default retry, so React Query's default
  // of 3 retries with exponential backoff (1 s, 2 s, 4 s) applies to every query that does not say retry: false.
  const client = new QueryClient()
  const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>
  return renderHook(
    () =>
      useQueries({
        queries: Array.from({ length: queries }, (_, i) => ({
          queryKey: ["q", i],
          queryFn: () => apiFetchJson(PROTECTED),
          ...options,
        })),
      }),
    { wrapper },
  )
}

describe("React Query retries and refresh calls on a dead session", () => {
  it("queries that keep React Query's default retry fail 4 times each (1 try + 3 retries)...", async () => {
    mount(5)

    await vi.advanceTimersByTimeAsync(10_000)

    expect(calls(PROTECTED)).toBe(5 * 4)
  })

  it("...but those 20 failing requests cause ONE refresh while the session is known to be dead", async () => {
    mount(5)

    await vi.advanceTimersByTimeAsync(10_000)

    expect(calls(PROTECTED)).toBe(20)
    expect(calls(REFRESH)).toBe(1)
  })

  it("queries that opt out of retries fail once each, with one refresh", async () => {
    mount(5, { retry: false })

    await vi.advanceTimersByTimeAsync(10_000)

    expect(calls(PROTECTED)).toBe(5)
    expect(calls(REFRESH)).toBe(1)
  })
})