import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { beforeEach, describe, expect, it, vi } from "vitest"

import type { SearchItemDto, SearchResponseDto } from "@/lib/search/types"

import { SearchView } from "./search-view"

let granted: string[] = []
let currentParams = new URLSearchParams()
const replace = vi.fn()
const fetchSearch = vi.fn()

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace, push: vi.fn() }),
  usePathname: () => "/search",
  useSearchParams: () => currentParams,
}))
vi.mock("@/lib/auth/use-permissions", () => ({
  usePermissions: () => ({ hasPermission: (code: string) => granted.includes(code) }),
}))
vi.mock("@/lib/search/api", () => ({
  fetchSearch: (...args: unknown[]) => fetchSearch(...args),
  fetchSearchFilterOptions: async () => ({
    documentTypes: [{ id: "t1", name: "Contract" }],
    departments: [{ id: "d1", name: "Finance" }],
    owners: [{ id: "u1", name: "Alice" }],
    metadataFields: [{ fieldId: "f1", key: "amount", label: "Amount", dataType: "number", options: [] }],
  }),
}))
vi.mock("@/lib/folders/use-folders", () => ({ useFolders: () => ({ flat: [], roots: [], byId: new Map() }) }))

function item(patch: Partial<SearchItemDto>): SearchItemDto {
  return {
    documentId: "d1",
    title: "Supplier contract",
    fileName: "contract.pdf",
    documentTypeId: "t1",
    documentTypeName: "Contract",
    folderId: null,
    ownerUserId: "u1",
    ownerDepartmentId: "d1",
    createdAt: "2026-01-05T09:00:00Z",
    updatedAt: "2026-02-01T09:00:00Z",
    lifecycleState: "active",
    legalHold: false,
    mimeType: "application/pdf",
    bodyStatus: "ready",
    highlights: { title: null, fileName: null, body: [] },
    ...patch,
  }
}

function response(patch: Partial<SearchResponseDto>): SearchResponseDto {
  return {
    mode: "search",
    items: [item({})],
    total: { value: 1, exact: true },
    nextCursor: null,
    notices: [],
    didYouMean: null,
    ...patch,
  }
}

function renderView() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <SearchView />
    </QueryClientProvider>
  )
}

describe("SearchView", () => {
  beforeEach(() => {
    granted = ["dsr.search-documents"]
    currentParams = new URLSearchParams("q=contract")
    replace.mockReset()
    fetchSearch.mockReset().mockResolvedValue(response({}))
  })

  it("searches with what the URL says and shows ranked, highlighted results that open the document", async () => {
    fetchSearch.mockResolvedValue(
      response({
        items: [
          item({
            highlights: {
              title: "Supplier <mark>contract</mark>",
              fileName: null,
              body: ["the <mark>contract</mark> is signed"],
            },
          }),
        ],
      })
    )
    renderView()
    const link = await screen.findByRole("link", { name: "Supplier contract" })
    expect(link).toHaveAttribute("href", "/documents/d1")
    expect(fetchSearch).toHaveBeenCalledWith(expect.objectContaining({ q: "contract" }), null)
    expect(link.querySelector("mark")?.textContent).toBe("contract")
    expect(screen.getByText(/is signed/)).toBeInTheDocument()
    expect(screen.getByText("1 result")).toBeInTheDocument()
    expect(screen.getByText(/Results only include documents you can open/)).toBeInTheDocument()
  })

  it("says 'or more' when the server could not check every candidate", async () => {
    fetchSearch.mockResolvedValue(response({ total: { value: 20, exact: false }, nextCursor: "c1" }))
    renderView()
    expect(await screen.findByText("20 or more results")).toBeInTheDocument()
  })

  it("pages with the cursor the server gave, back and forth, and restarts when the search changes", async () => {
    fetchSearch.mockImplementation(async (_f: unknown, cursor: string | null) =>
      cursor === null
        ? response({ items: [item({ documentId: "a", title: "First page doc" })], nextCursor: "c1", total: { value: 1, exact: false } })
        : response({ items: [item({ documentId: "b", title: "Second page doc" })], nextCursor: null })
    )
    const { rerender } = renderView()
    expect(await screen.findByText("First page doc")).toBeInTheDocument()
    expect(screen.getByRole("button", { name: "Previous" })).toBeDisabled()

    fireEvent.click(screen.getByRole("button", { name: "Next" }))
    expect(await screen.findByText("Second page doc")).toBeInTheDocument()
    expect(fetchSearch).toHaveBeenLastCalledWith(expect.anything(), "c1")
    expect(screen.getByRole("button", { name: "Next" })).toBeDisabled()

    fireEvent.click(screen.getByRole("button", { name: "Previous" }))
    expect(await screen.findByText("First page doc")).toBeInTheDocument()

    // another search starts again from the first page
    fireEvent.click(screen.getByRole("button", { name: "Next" }))
    await screen.findByText("Second page doc")
    currentParams = new URLSearchParams("q=something+else")
    rerender(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <SearchView />
      </QueryClientProvider>
    )
    await waitFor(() => expect(fetchSearch).toHaveBeenLastCalledWith(expect.objectContaining({ q: "something else" }), null))
  })

  it("explains what to try when nothing matches, and offers a correction", async () => {
    fetchSearch.mockResolvedValue(
      response({ items: [], total: { value: 0, exact: true }, didYouMean: "supplier contract", notices: [{ code: "NO_RESULTS", message: "No results found.", details: [] }] })
    )
    renderView()
    expect(await screen.findByText(/No results found/)).toBeInTheDocument()
    expect(screen.getByText(/Check the spelling/)).toBeInTheDocument()
    expect(screen.getByText(/Remove a filter/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole("button", { name: "supplier contract" }))
    expect(replace).toHaveBeenCalledWith(expect.stringContaining("q=supplier+contract"), expect.anything())
  })

  it("tells the user when the basic title search is being used, and which criteria it could not apply", async () => {
    fetchSearch.mockResolvedValue(
      response({
        mode: "basic",
        notices: [
          { code: "FALLBACK", message: "x", details: [] },
          { code: "FILTERS_NOT_APPLIED", message: "x", details: ["text inside documents", "created date"] },
        ],
      })
    )
    renderView()
    expect(await screen.findByText(/basic title search/)).toBeInTheDocument()
    expect(screen.getByText(/text inside documents, created date/)).toBeInTheDocument()
  })

  it("tells the user about a filter that no longer exists, and that the rest still apply", async () => {
    fetchSearch.mockResolvedValue(
      response({ notices: [{ code: "FILTER_NO_LONGER_EXISTS", message: "x", details: ["documentType", "folder"] }] })
    )
    renderView()
    expect(await screen.findByText(/no longer exists, so they were not applied \(documentType, folder\)/)).toBeInTheDocument()
    expect(screen.getByText(/rest of your criteria still apply/)).toBeInTheDocument()
  })

  it("asks the user to refine when there were too many possible matches", async () => {
    fetchSearch.mockResolvedValue(response({ notices: [{ code: "REFINE", message: "Add a keyword or a filter to narrow them down.", details: [] }] }))
    renderView()
    expect(await screen.findByText(/narrow them down/)).toBeInTheDocument()
  })

  it("notes files whose inside words are not (yet) searchable, instead of leaving the user guessing", async () => {
    fetchSearch.mockResolvedValue(
      response({
        items: [
          item({ documentId: "p", title: "Pending doc", bodyStatus: "pending" }),
          item({ documentId: "u", title: "Unreadable doc", bodyStatus: "unavailable" }),
        ],
        total: { value: 2, exact: true },
      })
    )
    renderView()
    expect(await screen.findByText(/still being read/)).toBeInTheDocument()
    expect(screen.getByText(/couldn.t be read/)).toBeInTheDocument()
  })

  it("marks inactive and archived documents and legal holds, but not ordinary active ones", async () => {
    fetchSearch.mockResolvedValue(
      response({
        items: [
          item({ documentId: "a", title: "Old one", lifecycleState: "archived", legalHold: true }),
          item({ documentId: "b", title: "Current one" }),
        ],
        total: { value: 2, exact: true },
      })
    )
    renderView()
    await screen.findByText("Old one")
    expect(screen.getByText("Archived")).toBeInTheDocument()
    expect(screen.getByText("Legal hold")).toBeInTheDocument()
    expect(screen.queryByText("Active")).toBeNull()
  })

  it("only people who may see the archive are offered the status filter", async () => {
    renderView()
    await screen.findByText("Supplier contract")
    fireEvent.click(screen.getByRole("button", { name: /Filters/ }))
    expect(await screen.findByLabelText("Search filters")).toBeInTheDocument()
    expect(screen.queryByRole("radiogroup", { name: "Document status" })).toBeNull()
    expect(screen.queryByRole("radio", { name: "Archived" })).toBeNull()
  })

  it("opens by itself when the URL already carries filters, so they are never hidden from the person who set them", async () => {
    currentParams = new URLSearchParams("q=contract&type=t1")
    renderView()
    await screen.findByText("Supplier contract")
    expect(screen.getByLabelText("Search filters")).toBeInTheDocument()
  })

  it("people with the archive permission can include inactive and archived documents", async () => {
    granted = ["dsr.search-documents", "crm.view-archive"]
    renderView()
    await screen.findByText("Supplier contract")
    fireEvent.click(screen.getByRole("button", { name: /Filters/ }))
    fireEvent.click(await screen.findByRole("radio", { name: "Archived" }))
    expect(replace).toHaveBeenCalledWith(expect.stringContaining("status=archived"), expect.anything())
  })

  it("applying a filter writes it to the URL; clearing removes the filters but keeps the words", async () => {
    currentParams = new URLSearchParams("q=contract&owner=u1")
    renderView()
    await screen.findByText("Supplier contract")
    expect(fetchSearch).toHaveBeenCalledWith(expect.objectContaining({ owner: "u1" }), null)
    expect(screen.getByRole("button", { name: /Filters \(1\)/ })).toBeInTheDocument()
    fireEvent.click(await screen.findByRole("button", { name: "Clear all filters" }))
    expect(replace).toHaveBeenCalledWith("/search?q=contract", expect.anything())
  })

  it("changing the sort writes it to the URL", async () => {
    renderView()
    await screen.findByText("Supplier contract")
    fireEvent.change(screen.getByLabelText("Sort by"), { target: { value: "title" } })
    expect(replace).toHaveBeenCalledWith(expect.stringContaining("sort=title"), expect.anything())
  })

  it("shows a retryable error rather than a blank page when the search fails", async () => {
    fetchSearch.mockRejectedValue(new Error("boom"))
    renderView()
    await waitFor(() => expect(fetchSearch).toHaveBeenCalled())
    expect(screen.queryByRole("link", { name: "Supplier contract" })).toBeNull()
  })
})
