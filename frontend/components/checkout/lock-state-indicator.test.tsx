import { render, screen } from "@testing-library/react"
import { describe, expect, it } from "vitest"

import { LockStateIndicator } from "./lock-state-indicator"

describe("LockStateIndicator", () => {
  it("shows nothing for a free document, so the list stays quiet", () => {
    const { container } = render(<LockStateIndicator lockState={null} />)
    expect(container).toBeEmptyDOMElement()
    const absent = render(<LockStateIndicator lockState={undefined} />)
    expect(absent.container).toBeEmptyDOMElement()
  })

  it("tells your own check-out from somebody else's", () => {
    const mine = render(<LockStateIndicator lockState="checked_out_by_me" />)
    expect(screen.getByLabelText("Checked out by you")).toBeInTheDocument()
    mine.unmount()

    render(<LockStateIndicator lockState="checked_out" />)
    expect(screen.getByLabelText("Checked out by someone else")).toBeInTheDocument()
  })

  it("marks a legal hold", () => {
    render(<LockStateIndicator lockState="legal_hold" />)
    expect(screen.getByLabelText("Under legal hold")).toBeInTheDocument()
  })
})
