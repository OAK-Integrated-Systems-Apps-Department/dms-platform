import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import type { RecycleBinDto, RecycleBinItemDto } from "@/lib/recycle-bin/api"

import { RecycleBin } from "./recycle-bin"

let bin: RecycleBinDto
let granted: string[] = []
const restore = vi.fn()
const empty = vi.fn()
const purge = vi.fn()
const toastSuccess = vi.fn()
const toastError = vi.fn()

vi.mock("sonner", () => ({
  toast: {
    success: (...args: unknown[]) => toastSuccess(...args),
    error: (...args: unknown[]) => toastError(...args),
  },
}))
vi.mock("@/lib/recycle-bin/api", () => ({
  fetchRecycleBin: () => Promise.resolve(bin),
  restoreFromRecycleBin: (...args: unknown[]) => restore(...args),
  emptyRecycleBin: (...args: unknown[]) => empty(...args),
  purgeFromRecycleBin: (...args: unknown[]) => purge(...args),
  saveRecycleBinRetention: vi.fn(),
  saveTypeRetention: vi.fn(),
}))
vi.mock("@/lib/auth/use-permissions", () => ({
  usePermissions: () => ({
    hasPermission: (code: string) => granted.includes(code),
  }),
}))

const RESTORE = "rbr.restore-deleted-documents"
const PURGE = "rbr.permanently-purge-documents"

const DAY = 86_400_000

function item(overrides: Partial<RecycleBinItemDto> = {}): RecycleBinItemDto {
  return {
    documentId: "d1",
    title: "Minutes",
    filename: "minutes.txt",
    sizeBytes: 2048,
    folderId: "f1",
    folderName: "Board",
    folderGone: false,
    deletedAt: new Date(Date.now() - 20 * DAY).toISOString(),
    deletedBy: "u1",
    deletedByName: "Alice",
    // 10 days minus a minute: rounds up to exactly 10 days left
    purgeAt: new Date(Date.now() + 10 * DAY - 60_000).toISOString(),
    protectedVersion: false,
    legalHold: false,
    ...overrides,
  }
}

function binOf(items: RecycleBinItemDto[]): RecycleBinDto {
  return {
    retentionDays: 30,
    items,
    byType: [],
    usage: {
      documents: items.length,
      bytes: items.reduce((n, i) => n + i.sizeBytes, 0),
    },
  }
}

function renderBin() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <RecycleBin />
    </QueryClientProvider>
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  granted = [RESTORE, PURGE]
  bin = binOf([item()])
})

describe("RecycleBin", () => {
  it("shows the storage the bin takes up and the days left before the purge", async () => {
    bin = binOf([
      item({ sizeBytes: 2048 }),
      item({ documentId: "d2", title: "Agenda", sizeBytes: 1024 }),
    ])
    renderBin()

    expect(await screen.findByText(/2 deleted documents/)).toBeInTheDocument()
    expect(screen.getByText(/3.0 KB of storage/)).toBeInTheDocument()
    expect(
      screen.getByRole("columnheader", { name: /days left/i })
    ).toBeInTheDocument()
    expect(screen.getAllByText("10")).toHaveLength(2)
  })

  it("uses the singular for one deleted document", async () => {
    renderBin()
    expect(
      await screen.findByText(/1 deleted document\b(?!s)/)
    ).toBeInTheDocument()
  })

  it("shows a legal hold as kept, with no countdown and no way to purge it", async () => {
    bin = binOf([item({ legalHold: true })])
    renderBin()

    expect(
      await screen.findByText(/Kept - under legal hold/)
    ).toBeInTheDocument()
    expect(screen.queryByText("10")).not.toBeInTheDocument()
    expect(
      screen.queryByRole("button", { name: /delete forever/i })
    ).not.toBeInTheDocument()
    // nothing purgeable is left, so there is nothing to empty
    expect(
      screen.queryByRole("button", { name: /empty recycle bin/i })
    ).not.toBeInTheDocument()
  })

  it("shows a protected version as kept too", async () => {
    bin = binOf([item({ protectedVersion: true })])
    renderBin()

    expect(
      await screen.findByText(/Kept - a version is protected/)
    ).toBeInTheDocument()
    expect(
      screen.queryByRole("button", { name: /empty recycle bin/i })
    ).not.toBeInTheDocument()
  })

  it("offers to empty the bin only to someone allowed to purge", async () => {
    granted = [RESTORE]
    renderBin()

    await screen.findByText(/1 deleted document/)
    expect(
      screen.queryByRole("button", { name: /empty recycle bin/i })
    ).not.toBeInTheDocument()
  })

  it("empties the bin after confirming, and says what was kept", async () => {
    bin = binOf([
      item(),
      item({ documentId: "d2", title: "Held", legalHold: true }),
    ])
    empty.mockResolvedValue({ purged: 1, skipped: 1 })
    renderBin()

    fireEvent.click(
      await screen.findByRole("button", { name: /empty recycle bin/i })
    )
    // the held document is not counted as one that will be deleted
    expect(
      await screen.findByText(/1 document will be deleted for good/)
    ).toBeInTheDocument()
    expect(empty).not.toHaveBeenCalled()

    fireEvent.click(
      screen.getAllByRole("button", { name: /empty recycle bin/i }).at(-1)!
    )
    await waitFor(() => expect(empty).toHaveBeenCalledTimes(1))
    await waitFor(() =>
      expect(toastSuccess).toHaveBeenCalledWith("1 deleted for good, 1 kept.")
    )
  })

  it("lets a document whose folder was deleted be restored, and says it went to the top level", async () => {
    bin = binOf([item({ folderGone: true, folderName: null })])
    restore.mockResolvedValue({ restoredToTopLevel: true })
    renderBin()

    expect(await screen.findByText("A deleted folder")).toBeInTheDocument()
    const button = screen.getByRole("button", { name: /restore/i })
    expect(button).toBeEnabled()

    fireEvent.click(button)
    await waitFor(() => expect(restore).toHaveBeenCalledWith("d1"))
    await waitFor(() =>
      expect(toastSuccess).toHaveBeenCalledWith(
        expect.stringMatching(
          /restored to the top level.*folder no longer exists/
        )
      )
    )
  })

  it("keeps the plain message when it goes back where it was", async () => {
    restore.mockResolvedValue({ restoredToTopLevel: false })
    renderBin()

    fireEvent.click(await screen.findByRole("button", { name: /restore/i }))
    await waitFor(() =>
      expect(toastSuccess).toHaveBeenCalledWith('"Minutes" was restored.')
    )
  })
})
