import { afterEach, describe, expect, it, vi } from "vitest"

const apiUpload = vi.hoisted(() => vi.fn())

vi.mock("@/lib/api-fetch", () => ({
  apiFetch: vi.fn(),
  apiFetchJson: vi.fn(),
  apiUpload,
}))

import { fetchDocuments, uploadDocument } from "./api"

afterEach(() => apiUpload.mockReset())

describe("uploadDocument", () => {
  const file = new File(["x"], "a.pdf", { type: "application/pdf" })

  it("sends duplicateOfDocumentId only when given (Keep both)", async () => {
    apiUpload.mockResolvedValue({})
    await uploadDocument(file, "type-1", "A", null, false, undefined, undefined, "doc-orig")
    expect(apiUpload.mock.calls[0][0]).toContain("duplicateOfDocumentId=doc-orig")
    expect(apiUpload.mock.calls[0][0]).not.toContain("refuseDuplicates")
  })

  it("leaves the request exactly as before for an ordinary upload", async () => {
    apiUpload.mockResolvedValue({})
    await uploadDocument(file, "type-1", "A", null, true)
    const url = apiUpload.mock.calls[0][0] as string
    expect(url).toContain("refuseDuplicates=true")
    expect(url).not.toContain("duplicateOfDocumentId")
  })
})

describe("fetchDocuments", () => {
  it("adds the relationship filters only when set", async () => {
    const { apiFetchJson } = await import("@/lib/api-fetch")
    vi.mocked(apiFetchJson).mockResolvedValue({})
    await fetchDocuments({ folderId: null, all: true, page: 0, size: 5, relatedTo: "d1", relationshipType: "SUPERSEDES", direction: "source" })
    await fetchDocuments({ folderId: null, page: 0, size: 5 })
    const [withFilter, without] = vi.mocked(apiFetchJson).mock.calls.map((c) => c[0] as string)
    expect(withFilter).toContain("relatedTo=d1")
    expect(withFilter).toContain("relationshipType=SUPERSEDES")
    expect(withFilter).toContain("direction=source")
    expect(without).not.toContain("relatedTo")
  })
})
