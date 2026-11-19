import { fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { beforeEach, describe, expect, it, vi } from "vitest"

import type { SearchItemDto, SearchResponseDto } from "@/lib/search/types"

import { SearchView } from "./search-view"

let granted: string[] = []
let currentParams = new URLSearchParams()
const replace = vi.fn()
const push = vi.fn()
const fetchSearch = vi.fn()
const fetchPresets = vi.fn()
const runPreset = vi.fn()
const reportPreviewFallback = vi.fn()

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace, push }),
  usePathname: () => "/search",
  useSearchParams: () => currentParams,
}))
vi.mock("@/lib/auth/use-permissions", () => ({
  usePermissions: () => ({
    hasPermission: (code: string) => granted.includes(code),
    hasAnyPermission: (...codes: string[]) => codes.some((c) => granted.includes(c)),
  }),
}))
vi.mock("@/lib/search/api", () => ({
  fetchSearch: (...args: unknown[]) => fetchSearch(...args),
  fetchSearchFilterOptions: async () => ({ documentTypes: [], departments: [], owners: [], metadataFields: [] }),
}))
vi.mock("@/lib/search/presets-api", () => ({
  fetchPresets: () => fetchPresets(),
  runPreset: (...args: unknown[]) => runPreset(...args),
  createPreset: vi.fn(),
  updatePreset: vi.fn(),
  deletePreset: vi.fn(),
  reportPreviewFallback: (...args: unknown[]) => reportPreviewFallback(...args),
}))
vi.mock("@/lib/folders/use-folders", () => ({ useFolders: () => ({ flat: [], roots: [], byId: new Map() }) }))
vi.mock("@/lib/bookmarks/use-bookmarks", () => ({
  useBookmarkStatus: () => ({ isBookmarked: () => false, bookmarkedIds: new Set(), isLoading: false }),
}))
// The pin and bookmark buttons and the thumbnail have their own tests; here we check what the page hands them.
vi.mock("@/components/pins/pin-button", () => ({
  PinButton: ({ row, pinned }: { row: { documentId: string }; pinned?: boolean }) => (
    <button type="button" data-testid={`pin-${row.documentId}`} data-pinned={String(pinned)} />
  ),
}))
vi.mock("@/components/bookmarks/bookmark-button", () => ({
  BookmarkButton: ({ row, bookmarked }: { row: { documentId: string }; bookmarked?: boolean }) => (
    <button type="button" data-testid={`bookmark-${row.documentId}`} data-bookmarked={String(bookmarked)} />
  ),
}))
vi.mock("@/components/documents/document-thumbnail", () => ({
  DocumentThumbnail: ({ documentId, versionNumber }: { documentId: string; versionNumber: number | null }) => (
    <span data-testid={`thumb-${documentId}`} data-version={String(versionNumber)} />
  ),
}))
// The REAL preview is reused (its own tests cover the policy and fallbacks); a stand-in shows it is the one opened.
vi.mock("@/components/documents/document-preview", () => ({
  DocumentPreview: ({
    row,
    onOpenChange,
    onFallback,
  }: {
    row: { documentId: string; title: string }
    onOpenChange: (open: boolean) => void
    onFallback?: (reason: string) => void
  }) => (
    <div role="dialog" aria-label={`Preview of ${row.title}`}>
      <button type="button" onClick={() => onOpenChange(false)}>
        close preview
      </button>
      <button type="button" onClick={() => onFallback?.("unsupported")}>
        simulate fallback
      </button>
    </div>
  ),
}))

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
    versionNumber: 3,
    sizeBytes: 1200,
    thumbnailUrl: "/api/v1/documents/d1/thumbnail",
    pinned: false,
    bookmarked: false,
    ...patch,
  }
}

function response(items: SearchItemDto[]): SearchResponseDto {
  return { mode: "search", items, total: { value: items.length, exact: true }, nextCursor: null, notices: [], didYouMean: null }
}

function renderView() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const tree = () => (
    <QueryClientProvider client={client}>
      <SearchView />
    </QueryClientProvider>
  )
  const view = render(tree())
  // A fresh element, so React re-reads the (mocked) URL like the real router would after a navigation.
  return { ...view, again: () => view.rerender(tree()) }
}

describe("search results: thumbnails, marks and preview (S11)", () => {
  beforeEach(() => {
    granted = ["dsr.search-documents"]
    currentParams = new URLSearchParams("q=contract")
    for (const m of [replace, push, fetchSearch, fetchPresets, runPreset, reportPreviewFallback]) m.mockReset()
    fetchSearch.mockResolvedValue(response([item({})]))
    fetchPresets.mockResolvedValue({ items: [], count: 0, limit: 50 })
  })

  it("shows a thumbnail only for results the server gave a link for, and keeps the file-type icon for the rest", async () => {
    fetchSearch.mockResolvedValue(
      response([item({}), item({ documentId: "d2", title: "No picture", thumbnailUrl: null })])
    )
    renderView()
    await screen.findByRole("link", { name: "Supplier contract" })
    expect(screen.getByTestId("thumb-d1")).toHaveAttribute("data-version", "3")
    expect(screen.queryByTestId("thumb-d2")).not.toBeInTheDocument()
  })

  it("hands the pin and bookmark buttons what the server decided for this page (no per-row request)", async () => {
    fetchSearch.mockResolvedValue(
      response([item({ pinned: true, bookmarked: true }), item({ documentId: "d2", title: "Plain", pinned: false, bookmarked: false })])
    )
    renderView()
    await screen.findByRole("link", { name: "Supplier contract" })
    expect(screen.getByTestId("pin-d1")).toHaveAttribute("data-pinned", "true")
    expect(screen.getByTestId("bookmark-d1")).toHaveAttribute("data-bookmarked", "true")
    expect(screen.getByTestId("pin-d2")).toHaveAttribute("data-pinned", "false")
    expect(screen.getByTestId("bookmark-d2")).toHaveAttribute("data-bookmarked", "false")
  })

  it("opens the existing preview from a result through the URL, without changing the search", async () => {
    renderView()
    fireEvent.click(await screen.findByRole("button", { name: "Preview Supplier contract" }))
    expect(push).toHaveBeenCalledTimes(1)
    const url = String(push.mock.calls[0][0])
    expect(url).toContain("q=contract")
    expect(url).toContain("preview=d1")
    // The search itself was not repeated for a different criterion.
    expect(fetchSearch.mock.calls.every(([filters]) => filters.q === "contract")).toBe(true)
  })

  it("shows the preview when the URL says so and closes it by dropping only that parameter", async () => {
    currentParams = new URLSearchParams("q=contract&type=t1&preview=d1")
    renderView()
    expect(await screen.findByRole("dialog", { name: "Preview of Supplier contract" })).toBeInTheDocument()
    fireEvent.click(screen.getByRole("button", { name: "close preview" }))
    const url = String(replace.mock.calls.at(-1)?.[0])
    expect(url).toContain("q=contract")
    expect(url).toContain("type=t1")
    expect(url).not.toContain("preview")
    // Paging was not reset: the same first-page request is the only one made.
    expect(fetchSearch).toHaveBeenCalledTimes(1)
  })

  it("counts a preview fallback with a closed reason and nothing else", async () => {
    currentParams = new URLSearchParams("q=contract&preview=d1")
    renderView()
    fireEvent.click(await screen.findByRole("button", { name: "simulate fallback" }))
    expect(reportPreviewFallback).toHaveBeenCalledWith("unsupported")
    expect(reportPreviewFallback.mock.calls[0]).toHaveLength(1)
  })
})

describe("saved presets on the search page (S13)", () => {
  beforeEach(() => {
    granted = ["dsr.search-documents", "dsr.manage-saved-search-presets.create"]
    currentParams = new URLSearchParams("q=contract")
    for (const m of [replace, push, fetchSearch, fetchPresets, runPreset, reportPreviewFallback]) m.mockReset()
    fetchSearch.mockResolvedValue(response([item({})]))
    fetchPresets.mockResolvedValue({
      items: [{ presetId: "p1", name: "Contracts to renew", summary: "contract, Finance", updatedAt: "2026-02-01T00:00:00Z", runnable: true }],
      count: 1,
      limit: 50,
    })
  })

  it("lists presets in two clicks and runs one: the resolved criteria go into the URL", async () => {
    runPreset.mockResolvedValue({
      preset: { presetId: "p1", name: "Contracts to renew" },
      criteria: { q: "renewal", documentTypeId: "t1", sort: "modified" },
      warnings: [],
      result: response([]),
    })
    renderView()
    fireEvent.click(await screen.findByRole("button", { name: /Saved presets/ }))
    fireEvent.click(await screen.findByRole("menuitem", { name: /Contracts to renew/ }))
    await waitFor(() => expect(runPreset).toHaveBeenCalledWith("p1"))
    await waitFor(() => expect(replace).toHaveBeenCalled())
    const url = String(replace.mock.calls.at(-1)?.[0])
    expect(url).toContain("q=renewal")
    expect(url).toContain("type=t1")
    expect(url).toContain("sort=modified")
    expect(url).toContain("preset=p1")
  })

  it("names the filters a preset dropped because they no longer exist, with the page's usual notice", async () => {
    runPreset.mockResolvedValue({
      preset: { presetId: "p1", name: "Contracts to renew" },
      criteria: { q: "renewal" },
      warnings: [{ code: "FILTER_NO_LONGER_EXISTS", message: "x", details: ["department", "metadata field Region"] }],
      result: response([]),
    })
    const view = renderView()
    fireEvent.click(await screen.findByRole("button", { name: /Saved presets/ }))
    fireEvent.click(await screen.findByRole("menuitem", { name: /Contracts to renew/ }))
    await waitFor(() => expect(replace).toHaveBeenCalled())
    // The router has now changed the URL: the page shows exactly the preset's search, with its warning.
    currentParams = new URLSearchParams(String(replace.mock.calls.at(-1)?.[0]).split("?")[1])
    view.again()
    const note = await screen.findByText(/no longer exists/)
    expect(note.textContent).toContain("department")
    expect(note.textContent).toContain("metadata field Region")
  })

  it("shows the active preset as a chip and detaches it when a filter changes", async () => {
    currentParams = new URLSearchParams("q=contract&preset=p1")
    renderView()
    const chip = await screen.findByTestId("active-preset")
    expect(within(chip).getByText("Contracts to renew")).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText("Sort by"), { target: { value: "title" } })
    expect(String(replace.mock.calls.at(-1)?.[0])).not.toContain("preset=")
  })

  it("shows nothing without the search permission", async () => {
    granted = []
    renderView()
    await waitFor(() => expect(fetchSearch).toHaveBeenCalled())
    expect(screen.queryByRole("button", { name: /Saved presets/ })).not.toBeInTheDocument()
  })

  it("keeps listing and running when the manage codes are missing, but disables saving with a reason", async () => {
    granted = ["dsr.search-documents"]
    renderView()
    expect(await screen.findByRole("button", { name: /Saved presets/ })).toBeEnabled()
    const save = screen.getByRole("button", { name: /Save as preset/ })
    expect(save).toBeDisabled()
    expect(save).toHaveAttribute("title", expect.stringMatching(/permission/))
  })
})
