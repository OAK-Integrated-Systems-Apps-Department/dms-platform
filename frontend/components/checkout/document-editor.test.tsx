import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import type { EditingSessionDto, UserPreferencesDto } from "@/lib/checkout/api"

import { DocumentEditor } from "./document-editor"

let prefs: { data?: UserPreferencesDto; isPending: boolean } = { isPending: false }
const startEditSession = vi.fn()

const endKeepAlive = vi.fn()
const beginKeepAlive = vi.fn(() => endKeepAlive)
vi.mock("@/lib/auth/use-proactive-session-refresh", () => ({ beginSessionKeepAlive: () => beginKeepAlive() }))
vi.mock("sonner", () => ({ toast: { error: vi.fn() } }))
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock("@/components/layout/dashboard-shell", () => ({
  DashboardShell: ({ children }: { children: React.ReactNode }) => <div data-testid="dms-shell">{children}</div>,
}))
vi.mock("@/components/documents/document-breadcrumb", () => ({
  DocumentBreadcrumb: () => <nav aria-label="breadcrumb" />,
}))
vi.mock("@/lib/documents/use-document", () => ({ useDocument: () => ({ data: { title: "Client agreement", folderId: null } }) }))
vi.mock("@/lib/checkout/api", () => ({
  startEditSession: (...args: unknown[]) => startEditSession(...args),
  discardCheckout: vi.fn(),
}))
vi.mock("@/lib/checkout/use-checkout", () => ({
  useCheckoutStatus: () => ({ data: undefined }),
  useUserPreferences: () => prefs,
}))

const session: EditingSessionDto = {
  editorUrl: "http://localhost:9980/browser/abc/cool.html?WOPISrc=x",
  accessToken: "t",
  accessTokenExpiresAtMillis: 1,
  mode: "exclusive",
}

describe("DocumentEditor layout", () => {
  beforeEach(() => {
    startEditSession.mockReset().mockResolvedValue(session)
    HTMLFormElement.prototype.submit = vi.fn()
    prefs = { isPending: false }
  })

  it("opens inside the DMS page by default, with an Open full screen button", async () => {
    render(<DocumentEditor documentId="d1" />)
    expect(await screen.findByTitle("Online document editor")).toBeInTheDocument()
    expect(screen.getByTestId("dms-shell")).toBeInTheDocument()
    expect(screen.queryByTestId("editor-fullpage")).not.toBeInTheDocument()
    expect(screen.getByRole("button", { name: /Open full screen/ })).toBeInTheDocument()
  })

  it("opens full page when the user chose that on My Profile - and then has nothing left to expand", async () => {
    prefs = { isPending: false, data: { editorLayout: "fullpage" } }
    render(<DocumentEditor documentId="d1" />)
    expect(await screen.findByTitle("Online document editor")).toBeInTheDocument()
    expect(screen.getByTestId("editor-fullpage")).toBeInTheDocument()
    expect(screen.queryByTestId("dms-shell")).not.toBeInTheDocument()
    expect(screen.getByText("Client agreement")).toBeInTheDocument()
    expect(screen.getByRole("link", { name: /Back/ })).toHaveAttribute("href", "/documents/d1")
    expect(screen.getByRole("button", { name: /Done editing/ })).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: /Open full screen/ })).not.toBeInTheDocument()
  })

  it("the full-screen tab is always full page, whatever the preference, without waiting for it", async () => {
    prefs = { isPending: true }
    render(<DocumentEditor documentId="d1" fullScreen />)
    expect(await screen.findByTitle("Online document editor")).toBeInTheDocument()
    expect(screen.getByTestId("editor-fullpage")).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: /Open full screen/ })).not.toBeInTheDocument()
  })

  it("Open full screen opens the full-screen tab and leaves a note instead of a second editor", async () => {
    const open = vi.fn(() => ({}) as Window)
    vi.stubGlobal("open", open)
    render(<DocumentEditor documentId="d1" />)
    await screen.findByTitle("Online document editor")

    fireEvent.click(screen.getByRole("button", { name: /Open full screen/ }))
    // No editor answers in a test (no save confirmation), so it moves on after the wait.
    await waitFor(() => expect(open).toHaveBeenCalledWith("/documents/d1/edit?layout=full", "_blank"), { timeout: 6000 })
    expect(await screen.findByText("This document is open full screen in another tab.")).toBeInTheDocument()
    expect(screen.queryByTitle("Online document editor")).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole("button", { name: "Edit here instead" }))
    expect(await screen.findByTitle("Online document editor")).toBeInTheDocument()
    vi.unstubAllGlobals()
  })

  it("a blocked pop-up leaves the editor where it is", async () => {
    vi.stubGlobal("open", vi.fn(() => null))
    render(<DocumentEditor documentId="d1" />)
    await screen.findByTitle("Online document editor")

    fireEvent.click(screen.getByRole("button", { name: /Open full screen/ }))
    await waitFor(() => expect(screen.getByRole("button", { name: /Open full screen/ })).toBeEnabled(), { timeout: 6000 })
    expect(screen.getByTitle("Online document editor")).toBeInTheDocument()
    vi.unstubAllGlobals()
  })

  it("waits for the preference rather than flashing the wrong layout", async () => {
    prefs = { isPending: true }
    render(<DocumentEditor documentId="d1" />)
    await waitFor(() => expect(startEditSession).toHaveBeenCalled())
    expect(screen.getByText("Opening the editor…")).toBeInTheDocument()
    expect(screen.queryByTitle("Online document editor")).not.toBeInTheDocument()
  })

  it("shows a refusal with a way back in the chosen layout, never a blank page", async () => {
    startEditSession.mockRejectedValue(new Error("nope"))
    render(<DocumentEditor documentId="d1" />)
    expect(await screen.findByText("The online editor could not be opened.")).toBeInTheDocument()
    expect(screen.getByRole("link", { name: /Back to the document/ })).toBeInTheDocument()
  })
})

describe("DocumentEditor session", () => {
  it("keeps the sign-in alive while the editor is open (typing in its frame never reaches this page), and lets go after", async () => {
    startEditSession.mockReset().mockResolvedValue(session)
    HTMLFormElement.prototype.submit = vi.fn()
    prefs = { isPending: false }
    beginKeepAlive.mockClear()
    endKeepAlive.mockClear()

    render(<DocumentEditor documentId="d1" />)
    await screen.findByTitle("Online document editor")
    expect(beginKeepAlive).toHaveBeenCalledTimes(1)
    expect(endKeepAlive).not.toHaveBeenCalled()

    cleanup()
    expect(endKeepAlive).toHaveBeenCalledTimes(1)
  })
})
