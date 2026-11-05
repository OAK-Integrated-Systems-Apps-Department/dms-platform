import { render, screen, waitFor } from "@testing-library/react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { beforeEach, describe, expect, it, vi } from "vitest"

const mocks = vi.hoisted(() => ({ fetchThumbnail: vi.fn() }))
vi.mock("@/lib/preview/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/preview/api")>()),
  fetchThumbnail: mocks.fetchThumbnail,
}))

import { DocumentThumbnail } from "./document-thumbnail"

function renderThumb(fileName: string | null) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return { client, ...render(
    <QueryClientProvider client={client}>
      <DocumentThumbnail documentId="d1" fileName={fileName} versionNumber={1}>
        <span>file icon</span>
      </DocumentThumbnail>
    </QueryClientProvider>
  ) }
}

describe("DocumentThumbnail", () => {
  beforeEach(() => {
    mocks.fetchThumbnail.mockReset()
  })

  it("shows the first-page picture once it is there, instead of the icon", async () => {
    mocks.fetchThumbnail.mockResolvedValue(new Blob(["jpeg"], { type: "image/jpeg" }))
    const { container } = renderThumb("contract.pdf")

    await waitFor(() => expect(container.querySelector("img")).not.toBeNull())
    expect(container.querySelector("img")?.getAttribute("src")).toMatch(/^data:/)
    expect(screen.queryByText("file icon")).toBeNull()
    expect(mocks.fetchThumbnail).toHaveBeenCalledWith("d1")
  })

  it("keeps the icon while it loads, and when the file has no picture", async () => {
    mocks.fetchThumbnail.mockResolvedValue(null)
    const { container } = renderThumb("contract.pdf")

    expect(screen.getByText("file icon")).toBeTruthy()
    await waitFor(() => expect(mocks.fetchThumbnail).toHaveBeenCalled())
    expect(container.querySelector("img")).toBeNull()
    expect(screen.getByText("file icon")).toBeTruthy()
  })

  it("keeps the icon when the thumbnail can't be fetched", async () => {
    mocks.fetchThumbnail.mockImplementation(async () => {
      throw new Error("boom")
    })
    const { container, client } = renderThumb("contract.pdf")

    // Let the failed fetch settle before the test ends - unmounting mid-flight cancels it.
    await waitFor(() =>
      expect(client.getQueryState(["thumbnail", "d1", 1])?.status).toBe("error")
    )
    expect(container.querySelector("img")).toBeNull()
    expect(screen.getByText("file icon")).toBeTruthy()
  })

  it("doesn't even ask for a file the server can't draw", async () => {
    const { container } = renderThumb("files.zip")

    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(mocks.fetchThumbnail).not.toHaveBeenCalled()
    expect(container.querySelector("img")).toBeNull()
    expect(screen.getByText("file icon")).toBeTruthy()
  })
})
