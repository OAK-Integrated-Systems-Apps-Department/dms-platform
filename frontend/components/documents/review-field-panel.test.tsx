import { fireEvent, render, screen } from "@testing-library/react"
import { describe, expect, it, vi } from "vitest"

import type { ReviewField } from "@/lib/documents/api"

import { ReviewFieldPanel, statusBadge, statusHint } from "./review-field-panel"

function field(overrides: Partial<ReviewField> = {}): ReviewField {
  return {
    fieldKey: "invoice_number",
    label: "Invoice Number",
    mandatory: false,
    dataType: "text",
    value: "INV-4471",
    rawValue: null,
    confidence: 0.93,
    status: "extracted",
    source: { page: 1, bbox: [0.1, 0.1, 0.4, 0.15] },
    ...overrides,
  }
}

function renderPanel(fields: ReviewField[], props: Partial<Parameters<typeof ReviewFieldPanel>[0]> = {}) {
  const handlers = {
    decisions: {},
    values: {},
    activeFieldKey: null,
    onFocusField: vi.fn(),
    onAccept: vi.fn(),
    onEdit: vi.fn(),
    onReExtract: vi.fn(),
    selectingFor: null,
    ...props,
  }
  render(<ReviewFieldPanel fields={fields} {...handlers} />)
  return handlers
}

describe("ReviewFieldPanel", () => {
  it("shows the value, its confidence and its status", () => {
    renderPanel([field()])

    expect(screen.getByText("INV-4471")).toBeInTheDocument()
    expect(screen.getByText("93% confident")).toBeInTheDocument()
    expect(screen.getByText("Extracted")).toBeInTheDocument()
  })

  it("says plainly when nothing was found instead of showing an empty row", () => {
    renderPanel([field({ value: null, status: "not_found", confidence: null, source: null })])

    expect(screen.getByText("nothing found")).toBeInTheDocument()
    expect(screen.getByText("no confidence recorded")).toBeInTheDocument()
  })

  it("does not call an unread field's blank value 'nothing found'", () => {
    renderPanel([field({ value: null, status: "not_extractable", confidence: null, source: null })])

    expect(screen.getByText("not read from the document")).toBeInTheDocument()
    expect(screen.queryByText("nothing found")).not.toBeInTheDocument()
  })

  it("shows what OCR read before parsing, when the parse changed it", () => {
    renderPanel([field({ value: "11", rawValue: "1 1" })])

    // The difference between these two is usually where a wrong value
    // comes from, and it is invisible if only the parsed form is shown.
    expect(screen.getByText("1 1")).toBeInTheDocument()
  })

  it("accepts a field", () => {
    const handlers = renderPanel([field()])

    fireEvent.click(screen.getByLabelText("Accept Invoice Number"))

    expect(handlers.onAccept).toHaveBeenCalledWith(expect.objectContaining({ fieldKey: "invoice_number" }))
  })

  it("edits a field and reports the new value", () => {
    const handlers = renderPanel([field()])

    fireEvent.click(screen.getByLabelText("Edit Invoice Number"))
    fireEvent.change(screen.getByLabelText("Invoice Number value"), { target: { value: "INV-4472" } })
    fireEvent.click(screen.getByLabelText("Save Invoice Number"))

    expect(handlers.onEdit).toHaveBeenCalledWith(
      expect.objectContaining({ fieldKey: "invoice_number" }),
      "INV-4472"
    )
  })

  it("abandons an edit on Escape without reporting anything", () => {
    const handlers = renderPanel([field()])

    fireEvent.click(screen.getByLabelText("Edit Invoice Number"))
    fireEvent.change(screen.getByLabelText("Invoice Number value"), { target: { value: "typo" } })
    fireEvent.keyDown(screen.getByLabelText("Invoice Number value"), { key: "Escape" })

    expect(handlers.onEdit).not.toHaveBeenCalled()
    expect(screen.getByText("INV-4471")).toBeInTheDocument()
  })

  it("asks for a region when re-reading a field from the page", () => {
    const handlers = renderPanel([field()])

    fireEvent.click(screen.getByLabelText("Re-read Invoice Number from the page"))

    expect(handlers.onReExtract).toHaveBeenCalledWith(
      expect.objectContaining({ fieldKey: "invoice_number" })
    )
  })

  it("shows a corrected value rather than the one OCR produced", () => {
    renderPanel([field()], {
      values: { invoice_number: "INV-9999" },
      decisions: { invoice_number: "corrected" },
    })

    expect(screen.getByText("INV-9999")).toBeInTheDocument()
    expect(screen.queryByText("INV-4471")).not.toBeInTheDocument()
    expect(screen.getByText("Corrected")).toBeInTheDocument()
  })

  it("joins a multi_select field into something readable", () => {
    renderPanel([field({ value: ["finance", "q3"] })])

    expect(screen.getByText("finance, q3")).toBeInTheDocument()
  })
})

describe("statusBadge", () => {
  it("marks a missing MANDATORY field as the problem it is", () => {
    expect(statusBadge(field({ mandatory: true, status: "not_found" }))).toEqual({
      text: "Not found",
      variant: "rust",
    })
  })

  it("leaves a missing optional field unalarming", () => {
    expect(statusBadge(field({ mandatory: false, status: "not_found" })).variant).toBe("muted")
  })

  it("flags a low-confidence value as needing a look", () => {
    expect(statusBadge(field({ status: "low_confidence" }))).toEqual({
      text: "Needs checking",
      variant: "gold",
    })
  })

  it("stops saying 'needs checking' once a human has checked it", () => {
    expect(statusBadge(field({ status: "low_confidence" }), "accepted").text).toBe("Accepted")
    expect(statusBadge(field({ status: "low_confidence" }), "corrected").text).toBe("Corrected")
  })

  it("never tells the reviewer OCR missed a field it was never going to read", () => {
    // `not_extractable` is not a failed search (V44/V45): the field is
    // not something a document states. Saying "Not found" would send the
    // reviewer hunting the page for something nobody printed on it.
    const mandatory = statusBadge(field({ mandatory: true, status: "not_extractable" }))
    expect(mandatory).toEqual({ text: "Enter manually", variant: "rust" })

    const optional = statusBadge(field({ mandatory: false, status: "not_extractable" }))
    expect(optional).toEqual({ text: "Not applicable", variant: "muted" })
  })

  it("says a defaulted value came from the default, not off the page", () => {
    // A tenant default applied where the page named no currency. Real
    // enough to file, so never gold -- but calling it "Extracted" would
    // be a claim about this document that isn't true.
    expect(statusBadge(field({ status: "defaulted", value: "LKR" }))).toEqual({
      text: "Default applied",
      variant: "muted",
    })
  })
})

describe("statusHint", () => {
  it("explains the required-but-unreadable case as an instruction, not a diagnosis", () => {
    // The badge says "Enter manually" in two words. The hint has to carry
    // the part that actually unblocks the reviewer: accepting cannot fill
    // it, so typing is the only way out of the queue.
    const hint = statusHint(field({ mandatory: true, status: "not_extractable", value: null }))
    expect(hint).toContain("Type it in")
    expect(hint).toContain("accepting cannot fill it")
  })

  it("does not tell an optional unreadable field to type anything", () => {
    const hint = statusHint(field({ mandatory: false, status: "not_extractable", value: null }))
    expect(hint).toContain("never looks for it")
    expect(hint).not.toContain("Type it in")
  })

  it("describes the reviewer's own decision once one has been made", () => {
    // Whatever OCR thought is no longer what the badge is reporting.
    expect(statusHint(field({ status: "low_confidence" }), "accepted")).toContain("You confirmed")
    expect(statusHint(field({ status: "extracted" }), "corrected")).toContain("You changed")
  })

  it("has a hint for every status, so no badge is ever unexplained", () => {
    for (const status of ["extracted", "low_confidence", "not_found", "not_extractable", "defaulted"] as const) {
      expect(statusHint(field({ status })).length).toBeGreaterThan(10)
    }
  })
})
