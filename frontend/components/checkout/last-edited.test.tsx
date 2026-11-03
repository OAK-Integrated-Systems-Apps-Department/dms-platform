import { render, screen } from "@testing-library/react"
import { describe, expect, it } from "vitest"

import type { CheckoutStatusDto } from "@/lib/checkout/api"

import { LastEdited } from "./last-edited"

function status(overrides: Partial<CheckoutStatusDto> = {}): CheckoutStatusDto {
  return {
    state: "available",
    checkedOutById: null,
    checkedOutByName: null,
    checkedOutAt: null,
    checkoutExpiresAt: null,
    checkedOutByMe: false,
    legalHold: false,
    legalHoldReason: null,
    legalHoldAt: null,
    signatureLocked: false,
    lastEditedById: "u-1",
    lastEditedByName: "Alice Perera",
    lastEditedAt: "2026-11-03T09:15:00",
    lastEditedVersion: 3,
    ...overrides,
  }
}

describe("LastEdited", () => {
  it("names who last edited it, when, and which version", () => {
    render(<LastEdited status={status()} />)
    const line = screen.getByText(/Last edited by/)
    expect(line).toHaveTextContent("Last edited by Alice Perera")
    expect(line).toHaveTextContent("2026-11-03, 09:15")
    expect(line).toHaveTextContent("version 3")
  })

  it("calls a never-edited document 'Uploaded by', not 'edited'", () => {
    render(<LastEdited status={status({ lastEditedVersion: 1 })} />)
    const line = screen.getByText(/Uploaded by/)
    expect(line).toHaveTextContent("Uploaded by Alice Perera")
    expect(line).not.toHaveTextContent("version")
    expect(screen.queryByText(/Last edited by/)).not.toBeInTheDocument()
  })

  it("shows nothing until the document has a version with an author", () => {
    const empty = render(<LastEdited status={status({ lastEditedAt: null, lastEditedByName: null, lastEditedVersion: 0 })} />)
    expect(empty.container).toBeEmptyDOMElement()
  })
})
