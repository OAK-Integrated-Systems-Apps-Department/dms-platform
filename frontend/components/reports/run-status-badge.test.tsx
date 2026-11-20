import { render, screen } from "@testing-library/react"
import { describe, expect, it } from "vitest"

import { RunStatusBadge } from "./run-status-badge"

describe("RunStatusBadge", () => {
  it.each([
    ["queued", "Waiting"],
    ["running", "Running"],
    ["completed", "Ready"],
    ["failed", "Failed"],
    ["cancelled", "Cancelled"],
    ["expired", "Expired"],
  ] as const)("says %s in plain words", (status, text) => {
    render(<RunStatusBadge status={status} />)
    expect(screen.getByText(text)).toBeInTheDocument()
  })
})
