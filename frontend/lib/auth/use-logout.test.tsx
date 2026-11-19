import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { act, renderHook } from "@testing-library/react"
import type { ReactNode } from "react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { cancelIntentionalLogout, isIntentionalLogout } from "@/lib/api-fetch"
import { LOGOUT_FAILED_MESSAGE, LOGOUT_UNCONFIRMED_MESSAGE, useLogout } from "./use-logout"

const mocks = vi.hoisted(() => ({
  push: vi.fn(),
  refresh: vi.fn(),
  toastError: vi.fn(),
}))

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: mocks.push, refresh: mocks.refresh }) }))
vi.mock("sonner", () => ({ toast: { error: mocks.toastError } }))

let queryClient: QueryClient
let fetchMock: ReturnType<typeof vi.fn>
let flagWhenRequestSent: boolean[]

const respond = (status: number, body: unknown = null) =>
  new Response(body === null ? null : JSON.stringify(body), { status, headers: { "content-type": "application/json" } })

function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
}

function setup() {
  return renderHook(() => useLogout(), { wrapper })
}

const logoutCalls = () => fetchMock.mock.calls.filter(([url]) => String(url).endsWith("/api/v1/auth/logout"))

beforeEach(() => {
  vi.clearAllMocks()
  cancelIntentionalLogout()
  queryClient = new QueryClient()
  queryClient.setQueryData(["me"], { name: "Alice" })
  vi.spyOn(queryClient, "clear")
  flagWhenRequestSent = []
  fetchMock = vi.fn(async () => {
    flagWhenRequestSent.push(isIntentionalLogout())
    return respond(200)
  })
  vi.stubGlobal("fetch", fetchMock)
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  cancelIntentionalLogout()
})

describe("useLogout - a successful logout", () => {
  it("posts to /api/v1/auth/logout once, with the session cookie", async () => {
    const { result } = setup()

    await act(() => result.current.logout())

    expect(logoutCalls()).toHaveLength(1)
    const [, init] = logoutCalls()[0]
    expect(init.method).toBe("POST")
    expect(init.credentials).toBe("include")
  })

  it("marks the logout intentional BEFORE the request is sent", async () => {
    const { result } = setup()

    await act(() => result.current.logout())

    expect(flagWhenRequestSent).toEqual([true])
  })

  it("clears the cache, goes to /login and refreshes", async () => {
    const { result } = setup()

    await act(() => result.current.logout())

    expect(queryClient.clear).toHaveBeenCalledTimes(1)
    expect(queryClient.getQueryData(["me"])).toBeUndefined()
    expect(mocks.push).toHaveBeenCalledTimes(1)
    expect(mocks.push).toHaveBeenCalledWith("/login")
    expect(mocks.refresh).toHaveBeenCalledTimes(1)
    expect(mocks.push.mock.invocationCallOrder[0]).toBeLessThan(mocks.refresh.mock.invocationCallOrder[0])
    expect(mocks.toastError).not.toHaveBeenCalled()
  })

  it("keeps the intentional-logout suppression after success, and lets it lapse after 15 seconds", async () => {
    vi.useFakeTimers()
    const { result } = setup()

    await act(() => result.current.logout())
    expect(isIntentionalLogout()).toBe(true)

    vi.advanceTimersByTime(14_999)
    expect(isIntentionalLogout()).toBe(true)
    vi.advanceTimersByTime(2)
    expect(isIntentionalLogout()).toBe(false)
  })

  it("ignores a second click once it has succeeded (the page is leaving)", async () => {
    const { result } = setup()

    await act(() => result.current.logout())
    await act(() => result.current.logout())

    expect(logoutCalls()).toHaveLength(1)
    expect(mocks.push).toHaveBeenCalledTimes(1)
  })
})

describe("useLogout - a failed logout", () => {
  const failures: Array<[string, () => void, string]> = [
    ["a network failure", () => fetchMock.mockImplementation(async () => { flagWhenRequestSent.push(isIntentionalLogout()); throw new TypeError("Failed to fetch") }), LOGOUT_UNCONFIRMED_MESSAGE],
    ["HTTP 500", () => fetchMock.mockImplementation(async () => { flagWhenRequestSent.push(isIntentionalLogout()); return respond(500, { message: "boom" }) }), LOGOUT_FAILED_MESSAGE],
    ["HTTP 401", () => fetchMock.mockImplementation(async () => { flagWhenRequestSent.push(isIntentionalLogout()); return respond(401, { message: "no" }) }), LOGOUT_FAILED_MESSAGE],
  ]

  it.each(failures)("%s: says so, stays on the page and keeps the cached data", async (_name, arrange, message) => {
    arrange()
    const { result } = setup()

    await act(() => result.current.logout())

    expect(mocks.toastError).toHaveBeenCalledTimes(1)
    expect(mocks.toastError.mock.calls[0][0]).toBe(message)
    expect(mocks.push).not.toHaveBeenCalled()
    expect(mocks.refresh).not.toHaveBeenCalled()
    expect(queryClient.clear).not.toHaveBeenCalled()
    expect(queryClient.getQueryData(["me"])).toEqual({ name: "Alice" })
  })

  it.each(failures)("%s: clears the suppression, so a genuine session expiry is still reported", async (_name, arrange) => {
    arrange()
    const { result } = setup()

    await act(() => result.current.logout())

    expect(flagWhenRequestSent).toEqual([true])
    expect(isIntentionalLogout()).toBe(false)
  })

  it.each(failures)("%s: does not retry by itself - one request, and no refresh call either", async (_name, arrange) => {
    arrange()
    vi.useFakeTimers()
    const { result } = setup()

    await act(() => result.current.logout())
    await act(async () => { await vi.advanceTimersByTimeAsync(60_000) })

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(String(fetchMock.mock.calls[0][0])).toMatch(/\/api\/v1\/auth\/logout$/)
  })

  it.each(failures)("%s: can be tried again - the button is usable again", async (_name, arrange) => {
    arrange()
    const { result } = setup()

    await act(() => result.current.logout())

    expect(result.current.pending).toBe(false)
  })

  it("tells a known failure apart from an unconfirmed one", () => {
    expect(LOGOUT_FAILED_MESSAGE).toBe("Could not sign out. Please try again.")
    expect(LOGOUT_UNCONFIRMED_MESSAGE).not.toBe(LOGOUT_FAILED_MESSAGE)
    expect(LOGOUT_UNCONFIRMED_MESSAGE).toMatch(/confirm/i)
  })
})

describe("useLogout - trying again by hand", () => {
  it("a second click after a 500 sends a second request, and a 200 then signs out", async () => {
    fetchMock.mockImplementationOnce(async () => respond(500, { message: "boom" }))
    const { result } = setup()

    await act(() => result.current.logout())
    expect(mocks.push).not.toHaveBeenCalled()
    await act(() => result.current.logout())

    expect(logoutCalls()).toHaveLength(2)
    expect(mocks.push).toHaveBeenCalledTimes(1)
    expect(mocks.push).toHaveBeenCalledWith("/login")
    expect(queryClient.clear).toHaveBeenCalledTimes(1)
  })

  it("uncertain outcome: the reply was lost after the server already ended the session - a retry still completes the sign-out", async () => {
    // First call: the server cleared the cookies but the response never arrived. Second call: nothing left to
    // revoke, dms-core's logout is idempotent and answers 200.
    fetchMock.mockImplementationOnce(async () => { throw new TypeError("Failed to fetch") })
    const { result } = setup()

    await act(() => result.current.logout())
    expect(mocks.toastError.mock.calls[0][0]).toBe(LOGOUT_UNCONFIRMED_MESSAGE)
    expect(mocks.push).not.toHaveBeenCalled()
    expect(isIntentionalLogout()).toBe(false)

    await act(() => result.current.logout())
    expect(mocks.push).toHaveBeenCalledWith("/login")
    expect(isIntentionalLogout()).toBe(true)
  })
})

describe("useLogout - repeated clicks", () => {
  it("sends one request when clicked several times while the first is pending", async () => {
    let finish!: (response: Response) => void
    fetchMock.mockImplementationOnce(() => new Promise<Response>((resolve) => { finish = resolve }))
    const { result } = setup()

    let first!: Promise<void>
    await act(async () => {
      first = result.current.logout()
      void result.current.logout()
      void result.current.logout()
    })
    expect(result.current.pending).toBe(true)
    expect(logoutCalls()).toHaveLength(1)

    await act(async () => {
      finish(respond(200))
      await first
    })

    expect(logoutCalls()).toHaveLength(1)
    expect(mocks.push).toHaveBeenCalledTimes(1)
    expect(mocks.refresh).toHaveBeenCalledTimes(1)
  })

  it("after a failure the clicks that came in meanwhile are not replayed", async () => {
    let fail!: () => void
    fetchMock.mockImplementationOnce(() => new Promise<Response>((_resolve, reject) => { fail = () => reject(new TypeError("Failed to fetch")) }))
    const { result } = setup()

    let first!: Promise<void>
    await act(async () => {
      first = result.current.logout()
      void result.current.logout()
    })
    await act(async () => {
      fail()
      await first
    })

    expect(logoutCalls()).toHaveLength(1)
    expect(mocks.toastError).toHaveBeenCalledTimes(1)
  })
})