import { fireEvent, render, screen } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import type { RemovedVersionPageDto } from "@/lib/retention/api"

import { RemovedVersionsList } from "./removed-versions-list"

let data: RemovedVersionPageDto
const restore = vi.fn()

vi.mock("@/lib/retention/use-retention", () => ({
  useRemovedVersions: () => ({ data, isPending: false, isError: false }),
  useRestoreVersion: () => ({ mutate: restore, isPending: false }),
}))

describe("RemovedVersionsList", () => {
  beforeEach(() => {
    restore.mockClear()
    data = {
      content: [
        {
          versionId: "v1",
          documentId: "d1",
          documentTitle: "Master agreement",
          versionNumber: 2,
          fileName: "Master agreement.docx",
          sizeBytes: 2048,
          removedAt: "2026-11-01T09:00:00Z",
          destroysAt: "2026-12-01T09:00:00Z",
        },
      ],
      page: 0,
      size: 25,
      totalElements: 1,
      totalPages: 1,
    }
  })

  it("lists each removed version with the document, the file, and when its files will be destroyed", () => {
    render(<RemovedVersionsList />)
    expect(screen.getByRole("link", { name: "Master agreement" })).toHaveAttribute("href", "/documents/d1")
    expect(screen.getByText(/Version 2 · Master agreement.docx/)).toBeInTheDocument()
    expect(screen.getByText(/removed .* · destroyed /)).toBeInTheDocument()
  })

  it("restoring a version sends exactly that version", () => {
    render(<RemovedVersionsList />)
    fireEvent.click(screen.getByRole("button", { name: "Restore version 2" }))
    expect(restore).toHaveBeenCalledWith("v1")
  })

  it("says so when nothing is waiting out its grace period", () => {
    data = { ...data, content: [], totalElements: 0, totalPages: 0 }
    render(<RemovedVersionsList />)
    expect(screen.getByText(/No versions are waiting out their grace period/)).toBeInTheDocument()
  })
})
