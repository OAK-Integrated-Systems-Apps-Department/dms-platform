import { fireEvent, render, screen } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import { templateFixture } from "./test-fixtures"
import { ShareDialog } from "./share-dialog"

const mutate = vi.fn()
vi.mock("@/lib/reports/use-reports", () => ({
  useRecipientDirectory: () => ({
    users: [{ value: "u2", label: "Ben Silva" }],
    roles: [],
    departments: [],
    loading: false,
  }),
  useShareTemplate: () => ({ mutate, isPending: false }),
}))

describe("ShareDialog", () => {
  beforeEach(() => mutate.mockClear())

  it("says a report with no grants is private", () => {
    render(<ShareDialog template={templateFixture()} onClose={vi.fn()} />)
    expect(screen.getByText("Only you can open this report.")).toBeInTheDocument()
  })

  it("lists existing grants, changes a level and saves the whole list", () => {
    const template = templateFixture({ grants: [{ principalType: "user", principalId: "u2", accessLevel: "run" }] })
    render(<ShareDialog template={template} onClose={vi.fn()} />)
    expect(screen.getByText("Ben Silva")).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText("Access for Ben Silva"), { target: { value: "edit" } })
    fireEvent.click(screen.getByRole("button", { name: "Save sharing" }))
    expect(mutate).toHaveBeenCalledWith(
      { id: "t1", grants: [{ principalType: "user", principalId: "u2", accessLevel: "edit" }] },
      expect.objectContaining({ onSuccess: expect.any(Function) })
    )
  })

  it("stops sharing with someone", () => {
    const template = templateFixture({ grants: [{ principalType: "user", principalId: "u2", accessLevel: "run" }] })
    render(<ShareDialog template={template} onClose={vi.fn()} />)
    fireEvent.click(screen.getByRole("button", { name: "Stop sharing with Ben Silva" }))
    fireEvent.click(screen.getByRole("button", { name: "Save sharing" }))
    expect(mutate).toHaveBeenCalledWith({ id: "t1", grants: [] }, expect.anything())
  })
})
