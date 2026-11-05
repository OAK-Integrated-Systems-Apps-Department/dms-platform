import { render, screen } from "@testing-library/react"
import { describe, expect, it } from "vitest"

import { LifecycleBadge } from "./lifecycle-badge"

describe("LifecycleBadge", () => {
  it("says Active, Inactive or Archived in words - never colour alone", () => {
    const { rerender } = render(<LifecycleBadge state="active" />)
    expect(screen.getByText("Active")).toBeInTheDocument()
    rerender(<LifecycleBadge state="inactive" />)
    expect(screen.getByText("Inactive")).toBeInTheDocument()
    rerender(<LifecycleBadge state="archived" />)
    expect(screen.getByText("Archived")).toBeInTheDocument()
  })

  it("treats a document that carries no status as Active", () => {
    render(<LifecycleBadge state={undefined} />)
    expect(screen.getByText("Active")).toBeInTheDocument()
  })

  it("an active document a rule will archive says when, on hover", () => {
    render(<LifecycleBadge state="active" archiveDueAt="2027-05-04T09:00:00Z" />)
    expect(screen.getByTitle(/will be archived on/)).toBeInTheDocument()
  })

  it("points an inactive or archived document to where it now lives", () => {
    const { rerender } = render(<LifecycleBadge state="inactive" />)
    expect(screen.getByTitle(/Inactive & Archive/)).toBeInTheDocument()
    rerender(<LifecycleBadge state="archived" />)
    expect(screen.getByTitle(/Inactive & Archive/)).toBeInTheDocument()
  })
})
