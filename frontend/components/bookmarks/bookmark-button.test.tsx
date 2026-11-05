import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-error"
import { useBookmarkStatusStore } from "@/stores/bookmark-status-store"

const mocks = vi.hoisted(() => ({
  fetchStatus: vi.fn(),
  add: vi.fn(),
  remove: vi.fn(),
}))

const toast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), message: vi.fn() }))
vi.mock("sonner", () => ({ toast }))

vi.mock("@/lib/bookmarks/api", () => ({
  fetchBookmarkStatus: mocks.fetchStatus,
  addBookmark: mocks.add,
  removeBookmark: mocks.remove,
}))

let granted: string[] = []
vi.mock("@/lib/auth/use-permissions", () => ({
  usePermissions: () => ({ hasPermission: (code: string) => granted.includes(code) }),
}))

import { BookmarkButton } from "./bookmark-button"

const ROW = { documentId: "doc-1", title: "Board minutes" }

function renderButton() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <BookmarkButton row={ROW} />
    </QueryClientProvider>
  )
}

describe("BookmarkButton", () => {
  beforeEach(() => {
    mocks.fetchStatus.mockReset().mockResolvedValue(new Set())
    mocks.add.mockReset()
    mocks.remove.mockReset()
    toast.success.mockReset()
    toast.error.mockReset()
    toast.message.mockReset()
    granted = ["docm.documents.view", "bd.bookmark-unbookmark-documents.bookmark"]
    useBookmarkStatusStore.setState({ overrides: {} })
  })

  it("starts outline when not bookmarked, and fills in immediately on click (optimistic)", async () => {
    let resolveAdd: (value: { created: boolean }) => void = () => {}
    mocks.add.mockReturnValue(new Promise((resolve) => (resolveAdd = resolve)))
    renderButton()

    const button = await screen.findByRole("button", { name: "Bookmark Board minutes" })
    expect(button).toHaveAttribute("aria-pressed", "false")

    fireEvent.click(button)
    // The store override flips synchronously, before the server call resolves.
    expect(screen.getByRole("button")).toHaveAttribute("aria-pressed", "true")

    resolveAdd({ created: true })
    await waitFor(() => expect(toast.success).toHaveBeenCalledWith("Added to your bookmarks"))
  })

  it("shows 'Already in your bookmarks' when the add was idempotent", async () => {
    mocks.add.mockResolvedValue({ created: false })
    renderButton()

    fireEvent.click(await screen.findByRole("button", { name: "Bookmark Board minutes" }))
    await waitFor(() => expect(toast.message).toHaveBeenCalledWith("Already in your bookmarks"))
  })

  it("removes an existing bookmark on click, and toasts", async () => {
    mocks.fetchStatus.mockResolvedValue(new Set(["doc-1"]))
    mocks.remove.mockResolvedValue(undefined)
    renderButton()

    const button = await screen.findByRole("button", { name: "Remove Board minutes from bookmarks" })
    expect(button).toHaveAttribute("aria-pressed", "true")

    fireEvent.click(button)
    expect(screen.getByRole("button")).toHaveAttribute("aria-pressed", "false")
    await waitFor(() => expect(toast.success).toHaveBeenCalledWith("Removed from bookmarks"))
  })

  it("rolls back the optimistic state and shows the server's error on a failed add (e.g. at the cap)", async () => {
    mocks.add.mockRejectedValue(new ApiError(409, "You can bookmark up to 500 documents. Remove one to add another.", "BOOKMARK_LIMIT_REACHED"))
    renderButton()

    const button = await screen.findByRole("button", { name: "Bookmark Board minutes" })
    fireEvent.click(button)
    expect(screen.getByRole("button")).toHaveAttribute("aria-pressed", "true")

    await waitFor(() => expect(screen.getByRole("button")).toHaveAttribute("aria-pressed", "false"))
    expect(toast.error).toHaveBeenCalledWith("You can bookmark up to 500 documents. Remove one to add another.")
  })

  it("is hidden when not bookmarked and the caller lacks the bookmark permission", async () => {
    granted = ["docm.documents.view"]
    renderButton()
    await waitFor(() => expect(mocks.fetchStatus).toHaveBeenCalled())
    expect(screen.queryByRole("button")).not.toBeInTheDocument()
  })

  it("never hides the remove control for an existing bookmark, even without the permission", async () => {
    granted = ["docm.documents.view"]
    mocks.fetchStatus.mockResolvedValue(new Set(["doc-1"]))
    renderButton()
    expect(await screen.findByRole("button", { name: "Remove Board minutes from bookmarks" })).toBeInTheDocument()
  })
})
