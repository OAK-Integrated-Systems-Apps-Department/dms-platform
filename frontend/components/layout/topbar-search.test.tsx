import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { beforeEach, describe, expect, it, vi } from "vitest"

import { TopbarSearch } from "./topbar-search"

let granted: string[] = []
const push = vi.fn()
const fetchDocuments = vi.fn()

vi.mock("next/navigation", () => ({ useRouter: () => ({ push }) }))
vi.mock("@/lib/auth/use-permissions", () => ({
  usePermissions: () => ({ hasPermission: (code: string) => granted.includes(code) }),
}))
vi.mock("@/lib/documents/api", () => ({ fetchDocuments: (...args: unknown[]) => fetchDocuments(...args) }))

function renderBox(onOpen = vi.fn()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <TopbarSearch onOpen={onOpen} />
    </QueryClientProvider>
  )
  return onOpen
}

async function typeAndWait(text: string) {
  fireEvent.change(screen.getByPlaceholderText("Search documents…"), { target: { value: text } })
  await waitFor(() => expect(fetchDocuments).toHaveBeenCalled(), { timeout: 2000 })
}

describe("TopbarSearch", () => {
  beforeEach(() => {
    granted = ["docm.documents.view"]
    push.mockReset()
    fetchDocuments.mockReset().mockResolvedValue({
      content: [
        {
          documentId: "d1",
          title: "Supplier contract",
          fileName: "c.pdf",
          documentTypeName: "Contract",
          sizeBytes: 100,
          createdAt: "2026-01-05T09:00:00Z",
        },
      ],
    })
  })

  it("quick results work exactly as before: a title search that previews the document you pick", async () => {
    const onOpen = renderBox()
    await typeAndWait("contract")
    fireEvent.click(await screen.findByText("Supplier contract"))
    expect(onOpen).toHaveBeenCalledWith(expect.objectContaining({ documentId: "d1" }))
    expect(fetchDocuments).toHaveBeenCalledWith(expect.objectContaining({ q: "contract", all: true, size: 6 }))
  })

  it("people without the search permission see no 'See all results' (the page would refuse them)", async () => {
    renderBox()
    await typeAndWait("contract")
    await screen.findByText("Supplier contract")
    expect(screen.queryByText(/See all results/)).toBeNull()
    expect(screen.getByText("Browse My Documents")).toBeInTheDocument()
  })

  it("people with it get 'See all results', which opens the search page with their words", async () => {
    granted = ["docm.documents.view", "dsr.search-documents"]
    renderBox()
    await typeAndWait("supplier & co")
    fireEvent.click(await screen.findByText(/See all results for/))
    expect(push).toHaveBeenCalledWith("/search?q=supplier%20%26%20co")
  })

  it("Ctrl+Enter opens the full results instead of previewing the highlighted row", async () => {
    granted = ["docm.documents.view", "dsr.search-documents"]
    const onOpen = renderBox()
    await typeAndWait("contract")
    await screen.findByText("Supplier contract")
    fireEvent.keyDown(screen.getByPlaceholderText("Search documents…"), { key: "Enter", ctrlKey: true })
    expect(push).toHaveBeenCalledWith("/search?q=contract")
    expect(onOpen).not.toHaveBeenCalled()
  })

  it("plain Enter still previews the highlighted row", async () => {
    granted = ["docm.documents.view", "dsr.search-documents"]
    const onOpen = renderBox()
    await typeAndWait("contract")
    await screen.findByText("Supplier contract")
    fireEvent.keyDown(screen.getByPlaceholderText("Search documents…"), { key: "Enter" })
    expect(onOpen).toHaveBeenCalled()
    expect(push).not.toHaveBeenCalled()
  })

  it("Ctrl+Enter without the permission does nothing special", async () => {
    const onOpen = renderBox()
    await typeAndWait("contract")
    await screen.findByText("Supplier contract")
    fireEvent.keyDown(screen.getByPlaceholderText("Search documents…"), { key: "Enter", ctrlKey: true })
    expect(push).not.toHaveBeenCalledWith(expect.stringContaining("/search"))
    expect(onOpen).toHaveBeenCalled()
  })
})
