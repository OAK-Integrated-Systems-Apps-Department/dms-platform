import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { beforeEach, describe, expect, it, vi } from "vitest"

import type { DocumentListItemDto } from "@/lib/documents/api"

import { ArchiveView } from "./archive-view"

let granted: string[] = []
let rows: Partial<DocumentListItemDto>[] = []
const fetchDocuments = vi.fn()
const changeStatus = vi.fn()

vi.mock("@/lib/documents/api", () => ({ fetchDocuments: (...args: unknown[]) => fetchDocuments(...args) }))
vi.mock("@/lib/retention/use-retention", () => ({ useChangeStatus: () => ({ mutateAsync: changeStatus, isPending: false }) }))
vi.mock("@/lib/auth/use-permissions", () => ({
  usePermissions: () => ({ hasPermission: (code: string) => granted.includes(code) }),
}))

const MARK_ACTIVE = "crm.change-document-status.mark-active"

function row(overrides: Partial<DocumentListItemDto>): Partial<DocumentListItemDto> {
  return {
    documentId: "d1",
    title: "Supplier invoice 1041",
    documentTypeName: "Invoice",
    createdAt: "2026-01-05T09:00:00Z",
    lifecycleState: "inactive",
    lockState: null,
    ...overrides,
  }
}

function renderView() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <ArchiveView />
    </QueryClientProvider>
  )
}

describe("ArchiveView", () => {
  beforeEach(() => {
    granted = []
    rows = [row({}), row({ documentId: "d2", title: "Old contract", lifecycleState: "archived" })]
    changeStatus.mockReset().mockResolvedValue(undefined)
    fetchDocuments.mockReset().mockImplementation(async () => ({ content: rows, page: 0, size: 25, totalElements: rows.length, totalPages: 1 }))
  })

  it("asks the server for the archive view - inactive and archived documents across every folder", async () => {
    renderView()
    expect(await screen.findByText("Supplier invoice 1041")).toBeInTheDocument()
    expect(fetchDocuments).toHaveBeenCalledWith(expect.objectContaining({ view: "archive", all: true, folderId: null }))
    expect(screen.getByText("Old contract")).toBeInTheDocument()
    // Each carries its status badge, in words.
    expect(screen.getAllByText("Inactive").length).toBeGreaterThan(0)
    expect(screen.getAllByText("Archived").length).toBeGreaterThan(0)
  })

  it("can be narrowed to inactive or archived", async () => {
    renderView()
    await screen.findByText("Supplier invoice 1041")
    fireEvent.click(screen.getByRole("radio", { name: "Archived" }))
    expect(screen.queryByText("Supplier invoice 1041")).not.toBeInTheDocument()
    expect(screen.getByText("Old contract")).toBeInTheDocument()
  })

  it("offers 'bring back' only with the mark-active permission, and not for a document under legal hold", async () => {
    rows = [row({}), row({ documentId: "d3", title: "Held file", lockState: "legal_hold" })]
    const { unmount } = renderView()
    await screen.findByText("Supplier invoice 1041")
    expect(screen.queryByRole("button", { name: "Mark active" })).not.toBeInTheDocument()
    unmount()

    granted = [MARK_ACTIVE]
    renderView()
    await screen.findByText("Supplier invoice 1041")
    const buttons = screen.getAllByRole("button", { name: "Mark active" })
    expect(buttons[0]).toBeEnabled()
    expect(buttons[1]).toBeDisabled() // the held one
    expect(screen.getByLabelText("Under legal hold")).toBeInTheDocument()
  })

  it("marking a document active asks for an optional reason and sends it", async () => {
    granted = [MARK_ACTIVE]
    renderView()
    await screen.findByText("Supplier invoice 1041")
    fireEvent.click(screen.getAllByRole("button", { name: "Mark active" })[0])
    fireEvent.change(screen.getByLabelText("Reason (optional)"), { target: { value: "Dispute reopened" } })
    fireEvent.click(screen.getAllByRole("button", { name: "Mark active" }).at(-1) as HTMLElement)
    await waitFor(() =>
      expect(changeStatus).toHaveBeenCalledWith({ documentId: "d1", status: "active", reason: "Dispute reopened" })
    )
  })

  it("says so when nothing has been switched off or archived", async () => {
    rows = []
    renderView()
    expect(await screen.findByText(/Nothing here yet/)).toBeInTheDocument()
  })
})
