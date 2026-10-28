import { fireEvent, render, screen } from "@testing-library/react"
import { describe, expect, it, vi } from "vitest"

import type { ReviewQueueItem } from "@/lib/documents/api"

import { ReviewQueueTable } from "./review-queue-table"

function row(overrides: Partial<ReviewQueueItem> = {}): ReviewQueueItem {
  return {
    documentId: "doc-1",
    versionId: "ver-1",
    title: "Invoice 41",
    originalFilename: "invoice-41.pdf",
    mimeType: "application/pdf",
    createdAt: "2026-09-30T10:00:00Z",
    ocrStatus: "completed",
    requiresReview: true,
    reasons: ["missing_mandatory_field"],
    summary: "1 required field was not found: total_amount.",
    missingMandatory: ["total_amount"],
    validationFailures: [],
    poorQualityPages: [],
    errorCode: null,
    ...overrides,
  }
}

describe("ReviewQueueTable", () => {
  it("shows the reason on the row, not just the document", () => {
    render(<ReviewQueueTable rows={[row()]} onOpen={vi.fn()} />)

    expect(screen.getByText("Invoice 41")).toBeInTheDocument()
    // The whole point of the queue: the reviewer can triage without
    // opening anything.
    expect(screen.getByText("1 required field was not found: total_amount.")).toBeInTheDocument()
    expect(screen.getByText("Needs review")).toBeInTheDocument()
  })

  it("distinguishes a failed document from a flagged one", () => {
    render(
      <ReviewQueueTable
        rows={[
          row({
            versionId: "ver-2",
            ocrStatus: "failed",
            requiresReview: false,
            reasons: ["ocr_failed"],
            summary: "This PDF is password-protected and can't be read automatically.",
            errorCode: "ENCRYPTED_PDF",
          }),
        ]}
        onOpen={vi.fn()}
      />
    )

    expect(screen.getByText("OCR failed")).toBeInTheDocument()
    // The raw code never reaches the reviewer.
    expect(screen.queryByText(/ENCRYPTED_PDF/)).not.toBeInTheDocument()
  })

  it("says so plainly when nothing needs review", () => {
    render(<ReviewQueueTable rows={[]} onOpen={vi.fn()} />)

    expect(screen.getByText(/Nothing needs review/)).toBeInTheDocument()
  })

  it("opens the document a row points at", () => {
    const onOpen = vi.fn()
    render(<ReviewQueueTable rows={[row()]} onOpen={onOpen} />)

    fireEvent.click(screen.getByText("Invoice 41"))

    expect(onOpen).toHaveBeenCalledWith(expect.objectContaining({ documentId: "doc-1" }))
  })
})
