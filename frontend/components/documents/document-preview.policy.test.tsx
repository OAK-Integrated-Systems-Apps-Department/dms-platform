import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { beforeEach, describe, expect, it, vi } from "vitest"

import type { PreviewPolicyDto } from "@/lib/preview/api"

const mocks = vi.hoisted(() => ({
  previewDocumentFile: vi.fn(),
  downloadFromPreview: vi.fn(),
  saveDownloadedFile: vi.fn(),
  usePreviewPolicy: vi.fn(),
  toastError: vi.fn(),
}))

vi.mock("sonner", () => ({ toast: { error: mocks.toastError, info: vi.fn(), success: vi.fn() } }))
vi.mock("@/lib/documents/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/documents/api")>()),
  previewDocumentFile: mocks.previewDocumentFile,
}))
vi.mock("@/lib/preview/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/preview/api")>()),
  downloadFromPreview: mocks.downloadFromPreview,
}))
vi.mock("@/lib/download-file", () => ({ saveDownloadedFile: mocks.saveDownloadedFile }))
vi.mock("@/lib/preview/use-preview", () => ({ usePreviewPolicy: mocks.usePreviewPolicy }))
// The viewers themselves have their own tests - here only which one is chosen matters.
vi.mock("@/components/documents/page-viewer", () => ({
  PageViewer: ({ policy }: { policy: PreviewPolicyDto }) => (
    <p>pages viewer (print {policy.print})</p>
  ),
}))
vi.mock("@/components/documents/slide-viewer", () => ({ SlideViewer: () => <p>slide viewer</p> }))
vi.mock("@/components/pins/pin-button", () => ({
  PinButton: () => null,
  PinnedIndicator: () => null,
}))
vi.mock("@/components/relationships/relationships-link", () => ({ RelationshipsLink: () => null }))

import { DocumentPreview } from "./document-preview"

const OPEN: PreviewPolicyDto = { print: "allow", download: "allow", copy: "allow" }

function row(fileName: string) {
  return {
    documentId: "d1",
    title: "Master agreement",
    fileName,
    mimeType: null,
    sizeBytes: 1200,
    documentTypeName: "Contract",
    createdAt: "2026-11-05T10:00:00Z",
  }
}

function policyIs(policy: PreviewPolicyDto | "loading" | "failed") {
  mocks.usePreviewPolicy.mockReturnValue(
    policy === "loading"
      ? { data: undefined, isPending: true, isError: false }
      : policy === "failed"
        ? { data: undefined, isPending: false, isError: true, error: new Error("down") }
        : { data: policy, isPending: false, isError: false }
  )
}

function renderPreview(
  fileName: string,
  extra: Partial<Parameters<typeof DocumentPreview>[0]> = {}
) {
  const onDownload = vi.fn()
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <DocumentPreview row={row(fileName)} onOpenChange={vi.fn()} onDownload={onDownload} {...extra} />
    </QueryClientProvider>
  )
  return onDownload
}

describe("DocumentPreview under the organisation's preview policy", () => {
  beforeEach(() => {
    mocks.previewDocumentFile.mockReset()
    mocks.downloadFromPreview.mockReset()
    mocks.saveDownloadedFile.mockReset()
    mocks.toastError.mockReset()
    mocks.usePreviewPolicy.mockReset()
    mocks.previewDocumentFile.mockResolvedValue({ blob: new Blob(["%PDF"]), contentType: "application/pdf" })
  })

  it("is the plain preview when nothing is restricted: the file is fetched and the Download button works as before", async () => {
    policyIs(OPEN)
    const onDownload = renderPreview("contract.pdf")

    await waitFor(() => expect(mocks.previewDocumentFile).toHaveBeenCalledWith("d1"))
    expect(screen.queryByText(/pages viewer/)).toBeNull()
    fireEvent.click(screen.getByRole("button", { name: "Download" }))
    expect(onDownload).toHaveBeenCalled()
    expect(mocks.downloadFromPreview).not.toHaveBeenCalled()
  })

  it("never starts as the plain preview: nothing is fetched until the policy is known", () => {
    policyIs("loading")
    renderPreview("contract.pdf")

    expect(screen.getByText("Opening…")).toBeTruthy()
    expect(mocks.previewDocumentFile).not.toHaveBeenCalled()
    expect(screen.queryByText(/pages viewer/)).toBeNull()
  })

  it("shows an error, and fetches nothing, when the policy can't be read", () => {
    policyIs("failed")
    renderPreview("contract.pdf")

    expect(mocks.previewDocumentFile).not.toHaveBeenCalled()
    expect(screen.queryByText(/pages viewer/)).toBeNull()
    // The reason is toasted (LoadFailed); the panel shows its neutral placeholder - never the file.
    expect(screen.getByText("Nothing to show right now.")).toBeTruthy()
  })

  it("draws the pages on the server instead of fetching the file, once anything is restricted", async () => {
    policyIs({ ...OPEN, print: "watermark" })
    renderPreview("contract.pdf")

    expect(screen.getByText("pages viewer (print watermark)")).toBeTruthy()
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(mocks.previewDocumentFile).not.toHaveBeenCalled()
  })

  it("removes the Download button when downloads from a preview are blocked", () => {
    policyIs({ ...OPEN, download: "block" })
    renderPreview("contract.pdf")

    expect(screen.queryByRole("button", { name: /Download/ })).toBeNull()
  })

  it("downloads a watermarked copy through the preview channel, not the plain file, when the policy says so", async () => {
    policyIs({ ...OPEN, download: "watermark" })
    const onDownload = renderPreview("contract.pdf")
    mocks.downloadFromPreview.mockResolvedValue({ blob: new Blob(["pdf"]), filename: "contract (watermarked).pdf" })

    fireEvent.click(screen.getByRole("button", { name: "Download (watermarked)" }))

    await waitFor(() =>
      expect(mocks.saveDownloadedFile).toHaveBeenCalledWith({ blob: expect.any(Blob), filename: "contract (watermarked).pdf" })
    )
    expect(mocks.downloadFromPreview).toHaveBeenCalledWith("d1", undefined)
    expect(onDownload).not.toHaveBeenCalled()
  })

  it("says why a download failed instead of failing silently", async () => {
    policyIs({ ...OPEN, download: "watermark" })
    renderPreview("contract.pdf")
    mocks.downloadFromPreview.mockRejectedValue(new Error("This file is too long to download with a watermark."))

    fireEvent.click(screen.getByRole("button", { name: "Download (watermarked)" }))

    await waitFor(() =>
      expect(mocks.toastError).toHaveBeenCalledWith("This file is too long to download with a watermark.")
    )
    expect(mocks.saveDownloadedFile).not.toHaveBeenCalled()
  })

  it("won't show a file that can't be drawn as pages while previews are restricted - and doesn't offer a download that is blocked", () => {
    policyIs({ print: "block", download: "block", copy: "block" })
    renderPreview("files.zip")

    expect(screen.getByText(/can't be shown here/)).toBeTruthy()
    expect(screen.queryByText(/pages viewer/)).toBeNull()
    expect(screen.queryByRole("button", { name: /Download/ })).toBeNull()
  })

  it("leaves a preview of raw bytes alone (a workflow's pinned supporting document) - the policy covers documents' own previews", async () => {
    policyIs({ ...OPEN, print: "block", download: "block", copy: "block" })
    const contentSource = vi.fn().mockResolvedValue(new Blob(["%PDF"]))
    renderPreview("contract.pdf", { contentSource, contentKey: "s1" })

    await waitFor(() => expect(contentSource).toHaveBeenCalled())
    expect(screen.queryByText(/pages viewer/)).toBeNull()
    expect(mocks.usePreviewPolicy).toHaveBeenCalledWith(false)
  })
})
