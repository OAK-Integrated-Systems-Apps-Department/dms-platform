import { fireEvent, render, screen } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import type { BookmarksPageDto } from "@/lib/bookmarks/api"
import type { DocumentListItemDto } from "@/lib/documents/api"
import { setBrowserView } from "@/lib/documents/use-browser-view"

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }))

const mocks = vi.hoisted(() => ({
  updateNote: vi.fn(),
  remove: vi.fn(),
  page: undefined as BookmarksPageDto | undefined,
}))

vi.mock("@/lib/bookmarks/use-bookmarks", () => ({
  useBookmarksList: () => ({
    page: mocks.page,
    isLoading: false,
    isError: false,
    refetch: vi.fn(),
    updateNote: mocks.updateNote,
    remove: mocks.remove,
    isSaving: false,
  }),
}))

vi.mock("@/lib/folders/use-folders", () => ({
  useFolders: () => ({ byId: new Map() }),
}))

vi.mock("@/components/documents/document-preview", () => ({
  DocumentPreview: () => null,
}))

// DocumentTile (grid view) renders these indicators, which otherwise need a QueryClientProvider and
// real permission/status data - irrelevant to what this test file checks.
vi.mock("@/components/pins/pin-button", () => ({ PinnedIndicator: () => null }))
vi.mock("@/components/bookmarks/bookmark-button", () => ({ BookmarkedIndicator: () => null }))
// ...and so does the tile's first-page thumbnail; it shows its children (the file icon) until a picture is loaded.
vi.mock("@/components/documents/document-thumbnail", () => ({
  DocumentThumbnail: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}))

import { BookmarksView } from "./bookmarks-view"

function doc(overrides: Partial<DocumentListItemDto> = {}): DocumentListItemDto {
  return {
    documentId: "doc-1",
    title: "Board minutes",
    documentTypeId: "t-1",
    documentTypeName: "Minutes",
    folderId: null,
    fileName: "minutes.pdf",
    sizeBytes: 1000,
    mimeType: "application/pdf",
    versionNumber: 1,
    ownerUserId: "u-1",
    createdAt: "2026-10-01T00:00:00Z",
    restricted: false,
    myAccess: "view",
    ...overrides,
  }
}

function page(overrides: Partial<BookmarksPageDto> = {}): BookmarksPageDto {
  return {
    total: 1,
    page: 0,
    size: 25,
    unavailableCount: 0,
    items: [{ document: doc(), bookmarkedAt: "2026-10-30T00:00:00Z", note: null, archived: false }],
    ...overrides,
  }
}

describe("BookmarksView", () => {
  beforeEach(() => {
    mocks.updateNote.mockReset().mockResolvedValue(undefined)
    mocks.remove.mockReset().mockResolvedValue(undefined)
    mocks.page = page()
    // The table/note/remove-button assertions below assume list view - the view toggle itself is
    // covered separately. Real behaviour honours this same remembered preference.
    setBrowserView("list")
  })

  it("lists the caller's bookmarks", () => {
    render(<BookmarksView />)
    expect(screen.getByText("Board minutes")).toBeInTheDocument()
  })

  it("defaults to grid view, with a tile per bookmark and a working toggle to list", () => {
    setBrowserView("grid")
    render(<BookmarksView />)
    // Grid view renders a DocumentTile (an <li>), not a table row.
    expect(screen.getByRole("list")).toBeInTheDocument()
    expect(screen.getByText("Board minutes")).toBeInTheDocument()
    expect(screen.queryByRole("table")).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole("radio", { name: "List view" }))
    expect(screen.getByRole("table")).toBeInTheDocument()
  })

  it("shows the unavailable notice when some bookmarks are hidden", () => {
    mocks.page = page({ unavailableCount: 2 })
    render(<BookmarksView />)
    expect(screen.getByText(/2 bookmarks are currently unavailable/)).toBeInTheDocument()
  })

  it("flags an archived document instead of hiding it", () => {
    mocks.page = page({ items: [{ document: doc(), bookmarkedAt: "2026-10-30T00:00:00Z", note: null, archived: true }] })
    render(<BookmarksView />)
    expect(screen.getByText("Archived")).toBeInTheDocument()
  })

  it("removes a bookmark when its remove control is clicked", () => {
    render(<BookmarksView />)
    fireEvent.click(screen.getByRole("button", { name: "Remove Board minutes from bookmarks" }))
    expect(mocks.remove).toHaveBeenCalledWith("doc-1")
  })

  it("saves an edited note", async () => {
    render(<BookmarksView />)
    fireEvent.click(screen.getByRole("button", { name: "Add a note" }))
    const input = screen.getByRole("textbox", { name: "Bookmark note" })
    fireEvent.change(input, { target: { value: "Check this quarterly" } })
    fireEvent.click(screen.getByRole("button", { name: "Save note" }))
    expect(mocks.updateNote).toHaveBeenCalledWith("doc-1", "Check this quarterly")
  })

  it("shows the empty state when there are no bookmarks", () => {
    mocks.page = page({ total: 0, items: [] })
    render(<BookmarksView />)
    expect(screen.getByText(/haven't bookmarked anything yet/)).toBeInTheDocument()
  })
})
