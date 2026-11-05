import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { beforeEach, describe, expect, it, vi } from "vitest"

import type { PageDeckDto, PreviewPolicyDto } from "@/lib/preview/api"

const mocks = vi.hoisted(() => ({
  openPages: vi.fn(),
  pageImage: vi.fn(),
  toastInfo: vi.fn(),
  toastError: vi.fn(),
}))

vi.mock("sonner", () => ({
  toast: { info: mocks.toastInfo, error: mocks.toastError, success: vi.fn() },
}))
vi.mock("@/lib/preview/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/preview/api")>()),
  openPages: mocks.openPages,
  pageImage: mocks.pageImage,
}))

import { PageViewer } from "./page-viewer"

const OPEN: PreviewPolicyDto = { print: "allow", download: "allow", copy: "allow" }

function deck(overrides: Partial<PageDeckDto> = {}): PageDeckDto {
  return {
    versionId: "v1",
    pages: 3,
    width: 595,
    height: 842,
    partial: false,
    totalPages: 3,
    watermarked: false,
    ...overrides,
  }
}

function renderViewer(policy: PreviewPolicyDto) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <PageViewer documentId="d1" title="Master agreement" zoom={1} policy={policy} />
    </QueryClientProvider>
  )
}

describe("PageViewer", () => {
  beforeEach(() => {
    // jsdom has no layout engine: the viewer measures its panel with a ResizeObserver.
    vi.stubGlobal(
      "ResizeObserver",
      class {
        observe() {}
        unobserve() {}
        disconnect() {}
      }
    )
    mocks.openPages.mockReset()
    mocks.pageImage.mockReset()
    mocks.toastInfo.mockReset()
    mocks.toastError.mockReset()
    mocks.pageImage.mockResolvedValue(new Blob(["jpeg"], { type: "image/jpeg" }))
    URL.createObjectURL = vi.fn(() => "blob:page")
    URL.revokeObjectURL = vi.fn()
    document.body.classList.remove("dms-print-restricted")
  })

  it("lays out one slot per page, named by number", async () => {
    mocks.openPages.mockResolvedValue(deck())
    renderViewer({ ...OPEN, copy: "block" })

    const region = await screen.findByRole("region", { name: "Master agreement - 3 pages" })
    expect(region).toBeTruthy()
    expect(screen.getByLabelText("Page 1 of 3")).toBeTruthy()
    expect(screen.getByLabelText("Page 3 of 3")).toBeTruthy()
    expect(mocks.openPages).toHaveBeenCalledWith("d1", undefined)
  })

  it("explains that the pages are pictures when printing or copying is restricted", async () => {
    mocks.openPages.mockResolvedValue(deck())
    renderViewer({ ...OPEN, copy: "block" })

    expect(await screen.findByText(/Shown as pictures/)).toBeTruthy()
  })

  it("says the viewer's name is on every page when the pages are watermarked", async () => {
    mocks.openPages.mockResolvedValue(deck({ watermarked: true }))
    renderViewer({ ...OPEN, download: "watermark" })

    expect(await screen.findByText(/Your name and the time are on every page/)).toBeTruthy()
  })

  it("says so when only the first pages of a very large file are shown, and where the rest can't be had", async () => {
    mocks.openPages.mockResolvedValue(deck({ pages: 20, partial: true, totalPages: 400 }))
    renderViewer({ ...OPEN, download: "block" })

    expect(await screen.findByText(/Showing the first 20 of 400 pages\./)).toBeTruthy()
    expect(screen.getByText(/The rest can't be downloaded from a preview/)).toBeTruthy()
  })

  it("says only 'first N pages' when the whole length isn't known, and points to the download when it's allowed", async () => {
    mocks.openPages.mockResolvedValue(deck({ pages: 20, partial: true, totalPages: null }))
    renderViewer({ ...OPEN, copy: "block" })

    expect(await screen.findByText(/Showing the first 20 pages only\./)).toBeTruthy()
    expect(screen.getByText(/Download the file to see the rest/)).toBeTruthy()
  })

  it("offers a watermarked print when that's the policy", async () => {
    mocks.openPages.mockResolvedValue(deck({ watermarked: true }))
    renderViewer({ ...OPEN, print: "watermark" })

    expect(await screen.findByRole("button", { name: /Print with watermark/ })).toBeTruthy()
  })

  it("offers no print button when printing is blocked, and blocks Ctrl+P with a note", async () => {
    mocks.openPages.mockResolvedValue(deck())
    renderViewer({ ...OPEN, print: "block" })
    await screen.findByRole("region", { name: /Master agreement/ })

    expect(screen.queryByRole("button", { name: /Print/ })).toBeNull()
    const event = new KeyboardEvent("keydown", { key: "p", ctrlKey: true, cancelable: true })
    window.dispatchEvent(event)
    expect(event.defaultPrevented).toBe(true)
    expect(mocks.toastInfo).toHaveBeenCalledWith(expect.stringMatching(/doesn't allow printing/))
  })

  it("leaves Ctrl+P alone when printing is allowed", async () => {
    mocks.openPages.mockResolvedValue(deck())
    renderViewer({ ...OPEN, copy: "block" })
    await screen.findByRole("region", { name: /Master agreement/ })

    const event = new KeyboardEvent("keydown", { key: "p", ctrlKey: true, cancelable: true })
    window.dispatchEvent(event)
    expect(event.defaultPrevented).toBe(false)
    expect(document.body.classList.contains("dms-print-restricted")).toBe(false)
  })

  it("blanks the browser's own print of the page while printing is restricted, and restores it on close", async () => {
    mocks.openPages.mockResolvedValue(deck())
    const { unmount } = renderViewer({ ...OPEN, print: "watermark" })
    await screen.findByRole("region", { name: /Master agreement/ })

    expect(document.body.classList.contains("dms-print-restricted")).toBe(true)
    unmount()
    expect(document.body.classList.contains("dms-print-restricted")).toBe(false)
  })

  it("has no context menu to save a page from", async () => {
    mocks.openPages.mockResolvedValue(deck())
    renderViewer({ ...OPEN, copy: "block" })
    const region = await screen.findByRole("region", { name: /Master agreement/ })

    const event = new MouseEvent("contextmenu", { bubbles: true, cancelable: true })
    region.dispatchEvent(event)
    expect(event.defaultPrevented).toBe(true)
  })

  it("says so for a file with no pages", async () => {
    mocks.openPages.mockResolvedValue(deck({ pages: 0, totalPages: 0 }))
    renderViewer({ ...OPEN, copy: "block" })

    expect(await screen.findByText("This file has no pages to show.")).toBeTruthy()
  })

  it("shows a neutral placeholder, never the file, when the server can't prepare the preview", async () => {
    mocks.openPages.mockRejectedValue(new Error("This file couldn't be turned into a preview"))
    renderViewer({ ...OPEN, copy: "block" })

    await waitFor(() => expect(screen.getByText("Nothing to show right now.")).toBeTruthy())
    expect(mocks.toastError).toHaveBeenCalled()
  })

  it("watermarked print: asks for every page, then the browser prints them", async () => {
    mocks.openPages.mockResolvedValue(deck({ watermarked: true }))
    renderViewer({ ...OPEN, print: "watermark" })
    const button = await screen.findByRole("button", { name: /Print with watermark/ })

    fireEvent.click(button)

    await waitFor(() => expect(mocks.pageImage).toHaveBeenCalledWith("d1", undefined, 3, 1400))
    expect(mocks.pageImage).toHaveBeenCalledWith("d1", undefined, 1, 1400)
    expect(mocks.pageImage).toHaveBeenCalledWith("d1", undefined, 2, 1400)
  })
})
