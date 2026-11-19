import { render, waitFor } from "@testing-library/react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-error"
import type { PreviewPolicyDto } from "@/lib/preview/api"

const mocks = vi.hoisted(() => ({ previewDocumentFile: vi.fn(), usePreviewPolicy: vi.fn() }))

vi.mock("sonner", () => ({ toast: { error: vi.fn(), info: vi.fn(), success: vi.fn() } }))
vi.mock("@/lib/documents/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/documents/api")>()),
  previewDocumentFile: mocks.previewDocumentFile,
}))
vi.mock("@/lib/preview/use-preview", () => ({ usePreviewPolicy: mocks.usePreviewPolicy }))
vi.mock("@/components/documents/page-viewer", () => ({ PageViewer: () => <p>pages viewer</p> }))
vi.mock("@/components/documents/slide-viewer", () => ({ SlideViewer: () => <p>slide viewer</p> }))
vi.mock("@/components/pins/pin-button", () => ({ PinButton: () => null, PinnedIndicator: () => null }))
vi.mock("@/components/relationships/relationships-link", () => ({ RelationshipsLink: () => null }))

import { DocumentPreview } from "./document-preview"

const OPEN: PreviewPolicyDto = { print: "allow", download: "allow", copy: "allow" }

function show(fileName: string, policy: PreviewPolicyDto = OPEN) {
  mocks.usePreviewPolicy.mockReturnValue({ data: policy, isPending: false, isError: false })
  const onFallback = vi.fn()
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <DocumentPreview
        row={{ documentId: "d1", title: "T", fileName, mimeType: null, sizeBytes: 10, documentTypeName: null, createdAt: "2026-01-01T00:00:00Z" }}
        onOpenChange={vi.fn()}
        onDownload={vi.fn()}
        onFallback={onFallback}
      />
    </QueryClientProvider>
  )
  return onFallback
}

describe("DocumentPreview onFallback (used by search to count preview fallbacks)", () => {
  beforeEach(() => {
    mocks.previewDocumentFile.mockReset().mockResolvedValue({ blob: new Blob(["%PDF"]), contentType: "application/pdf" })
  })

  it("says nothing when the file is shown", async () => {
    const onFallback = show("contract.pdf")
    await waitFor(() => expect(mocks.previewDocumentFile).toHaveBeenCalled())
    expect(onFallback).not.toHaveBeenCalled()
  })

  it("reports 'unsupported' once for a type that cannot be shown", async () => {
    const onFallback = show("drawing.dwg")
    await waitFor(() => expect(onFallback).toHaveBeenCalledWith("unsupported"))
    expect(onFallback).toHaveBeenCalledTimes(1)
  })

  it("reports 'policy_blocked' when the organisation's policy blocks a type it cannot draw", async () => {
    const onFallback = show("archive.zip", { print: "block", download: "allow", copy: "block" })
    await waitFor(() => expect(onFallback).toHaveBeenCalledWith("policy_blocked"))
  })

  it("reports 'failed' when the file could not be loaded, and 'timeout' when the server timed out", async () => {
    mocks.previewDocumentFile.mockRejectedValue(new ApiError(500, "boom"))
    const failed = show("contract.pdf")
    await waitFor(() => expect(failed).toHaveBeenCalledWith("failed"))

    mocks.previewDocumentFile.mockRejectedValue(new ApiError(504, "slow"))
    const slow = show("contract.pdf")
    await waitFor(() => expect(slow).toHaveBeenCalledWith("timeout"))
  })
})
