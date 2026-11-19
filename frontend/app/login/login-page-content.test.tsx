import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import { LoginPageContent } from "./login-page-content"

const mocks = vi.hoisted(() => ({
  push: vi.fn(),
  refresh: vi.fn(),
  search: new URLSearchParams(),
  apiFetch: vi.fn(),
  apiFetchJson: vi.fn(),
  toastSuccess: vi.fn(),
  toastError: vi.fn(),
}))

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: mocks.push, refresh: mocks.refresh }),
  useSearchParams: () => mocks.search,
}))
vi.mock("sonner", () => ({ toast: { success: mocks.toastSuccess, error: mocks.toastError } }))
vi.mock("@/lib/api-fetch", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api-fetch")>()),
  apiFetch: mocks.apiFetch,
  apiFetchJson: mocks.apiFetchJson,
}))

let queryClient: QueryClient

function renderLogin(from?: string) {
  mocks.search = new URLSearchParams(from === undefined ? {} : { from })
  queryClient = new QueryClient()
  vi.spyOn(queryClient, "clear")
  return render(
    <QueryClientProvider client={queryClient}>
      <LoginPageContent />
    </QueryClientProvider>,
  )
}

async function signIn() {
  fireEvent.change(screen.getByPlaceholderText("Username or email"), { target: { value: "alice" } })
  fireEvent.change(screen.getByPlaceholderText("Password"), { target: { value: "correct horse" } })
  fireEvent.click(screen.getByRole("button", { name: /^sign in$/i }))
}

async function enterOtp(container: HTMLElement) {
  const boxes = await waitFor(() => {
    const found = container.querySelectorAll<HTMLInputElement>('input[inputmode="numeric"]')
    expect(found.length).toBe(6)
    return found
  })
  "123456".split("").forEach((digit, index) => fireEvent.change(boxes[index], { target: { value: digit } }))
  fireEvent.click(screen.getByRole("button", { name: /^submit$/i }))
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.apiFetchJson.mockResolvedValue({ mfaRequired: false })
  mocks.apiFetch.mockResolvedValue(undefined)
})

describe("login - where a successful sign-in goes", () => {
  it("goes to / when there is no from", async () => {
    renderLogin()
    await signIn()
    await waitFor(() => expect(mocks.push).toHaveBeenCalledTimes(1))
    expect(mocks.push).toHaveBeenCalledWith("/")
  })

  it.each([
    "/documents/123",
    "/tasks",
    "/documents?folder=abc&page=2",
    "/s/example-token",
    "/portal",
  ])("goes back to a valid from: %s", async (from) => {
    renderLogin(from)
    await signIn()
    await waitFor(() => expect(mocks.push).toHaveBeenCalledTimes(1))
    expect(mocks.push).toHaveBeenCalledWith(from)
  })

  it.each([
    ["an external URL", "https://evil.example"],
    ["a protocol-relative URL", "//evil.example/documents"],
    ["a backslash form", "/\\evil.example"],
    ["an encoded protocol-relative URL", "/%2F%2Fevil.example"],
    ["a double-encoded one", "/%252F%252Fevil.example"],
    ["a script URL", "javascript:alert(1)"],
    ["malformed encoding", "/documents/%E0%A4%A"],
    ["the login page", "/login"],
    ["a sign-in page, normalised", "/documents/../session-expired"],
    ["a sign-in page, encoded", "/%6Cogin"],
    ["blank", "   "],
  ])("falls back to / for %s", async (_label, from) => {
    renderLogin(from)
    await signIn()
    await waitFor(() => expect(mocks.push).toHaveBeenCalledTimes(1))
    expect(mocks.push).toHaveBeenCalledWith("/")
  })
})

describe("login - the one-time-code step", () => {
  it("keeps the validated return path through the code step", async () => {
    mocks.apiFetchJson.mockResolvedValue({ mfaRequired: true })
    const { container } = renderLogin("/documents/123")

    await signIn()
    await enterOtp(container)

    await waitFor(() => expect(mocks.push).toHaveBeenCalledTimes(1))
    expect(mocks.push).toHaveBeenCalledWith("/documents/123")
    const login = mocks.apiFetch.mock.calls.find(([url]) => String(url).endsWith("/api/v1/auth/login"))!
    expect(JSON.parse((login[1] as RequestInit).body as string)).toMatchObject({ username: "alice", totp: "123456" })
  })

  it("does not go anywhere before the code has been verified", async () => {
    mocks.apiFetchJson.mockResolvedValue({ mfaRequired: true })
    const { container } = renderLogin("/documents/123")

    await signIn()
    await waitFor(() => expect(container.querySelectorAll('input[inputmode="numeric"]').length).toBe(6))

    expect(mocks.push).not.toHaveBeenCalled()
    expect(mocks.apiFetch).not.toHaveBeenCalled()
  })

  it("still ignores an unsafe from after the code step", async () => {
    mocks.apiFetchJson.mockResolvedValue({ mfaRequired: true })
    const { container } = renderLogin("//evil.example")

    await signIn()
    await enterOtp(container)

    await waitFor(() => expect(mocks.push).toHaveBeenCalledTimes(1))
    expect(mocks.push).toHaveBeenCalledWith("/")
  })
})

describe("login - what did not change", () => {
  it("does not navigate when sign-in fails", async () => {
    mocks.apiFetch.mockRejectedValue(new Error("Invalid username or password."))
    renderLogin("/documents/123")

    await signIn()

    await waitFor(() => expect(mocks.toastError).toHaveBeenCalledWith("Invalid username or password."))
    expect(mocks.push).not.toHaveBeenCalled()
    expect(mocks.refresh).not.toHaveBeenCalled()
    expect(queryClient.clear).not.toHaveBeenCalled()
  })

  it("clears the query cache, shows the toast and refreshes - in that order, before navigating", async () => {
    renderLogin("/documents/123")

    await signIn()

    await waitFor(() => expect(mocks.push).toHaveBeenCalledTimes(1))
    expect(queryClient.clear).toHaveBeenCalledTimes(1)
    expect(mocks.toastSuccess).toHaveBeenCalledWith("Signed in successfully.")
    expect(mocks.refresh).toHaveBeenCalledTimes(1)
    const cleared = vi.mocked(queryClient.clear).mock.invocationCallOrder[0]
    expect(cleared).toBeLessThan(mocks.push.mock.invocationCallOrder[0])
    expect(mocks.push.mock.invocationCallOrder[0]).toBeLessThan(mocks.refresh.mock.invocationCallOrder[0])
  })
})
