import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-error"

import { RunDialog } from "./run-dialog"
import { catalogFixture, templateFixture } from "./test-fixtures"

const start = vi.fn()
vi.mock("@/lib/reports/use-reports", () => ({
  useStartRun: () => ({ mutateAsync: start, isPending: false }),
  useFieldValues: () => ({
    data: { content: [{ value: "published", label: "Published" }, { value: "draft", label: "Draft" }], page: 0, size: 25, hasMore: false },
    isPending: false,
  }),
}))

const locked = () =>
  templateFixture({
    scope: "official",
    locked: true,
    userEditableFilters: ["status", "createdAt"],
    definition: {
      source: "documents",
      filters: [
        { field: "title", operator: "contains", values: ["contract"] },
        { field: "status", operator: "is_any_of", values: ["published"] },
        { field: "createdAt", operator: "relative", values: ["last_30_days"] },
      ],
      columns: ["title"],
      sort: [],
      groupBy: null,
      includeInactive: false,
      timeZone: null,
    },
  })

describe("RunDialog", () => {
  beforeEach(() => {
    start.mockReset().mockResolvedValue({})
  })

  it("runs a plain report with just a format choice", async () => {
    const onStarted = vi.fn()
    render(<RunDialog template={templateFixture()} catalog={catalogFixture()} onClose={vi.fn()} onStarted={onStarted} />)
    expect(screen.queryByText(/lets you change these filters/)).not.toBeInTheDocument()
    fireEvent.click(screen.getByLabelText("PDF"))
    fireEvent.click(screen.getByRole("button", { name: "Run report" }))
    await waitFor(() => expect(start).toHaveBeenCalledWith({ templateId: "t1", format: "pdf", filterOverrides: undefined }))
    expect(onStarted).toHaveBeenCalled()
  })

  it("offers only the filters the author left open and sends their values", async () => {
    render(<RunDialog template={locked()} catalog={catalogFixture()} onClose={vi.fn()} onStarted={vi.fn()} />)
    expect(screen.getByText("Run with options")).toBeInTheDocument()
    expect(screen.queryByLabelText("Title 1")).not.toBeInTheDocument() // the fixed filter is not editable
    fireEvent.click(screen.getByLabelText("Draft"))
    fireEvent.change(screen.getByLabelText(/Created/), { target: { value: "last_7_days" } })
    fireEvent.click(screen.getByRole("button", { name: "Run report" }))
    await waitFor(() => expect(start).toHaveBeenCalled())
    expect(start.mock.calls[0][0].filterOverrides).toEqual([
      { field: "status", operator: "is_any_of", values: ["published", "draft"] },
      { field: "createdAt", operator: "relative", values: ["last_7_days"] },
    ])
  })

  it("will not run with an open filter left empty", async () => {
    render(<RunDialog template={locked()} catalog={catalogFixture()} onClose={vi.fn()} onStarted={vi.fn()} />)
    fireEvent.click(screen.getByLabelText("Published")) // untick the only value
    fireEvent.click(screen.getByRole("button", { name: "Run report" }))
    expect(await screen.findByRole("alert")).toHaveTextContent("Give a value")
    expect(start).not.toHaveBeenCalled()
  })

  it("shows the server problem for a field and stays open", async () => {
    const failure = new ApiError(422, "bad", "REPORT_RUN_INVALID", { format: "That file format is not available." })
    start.mockRejectedValue(failure)
    const onClose = vi.fn()
    render(<RunDialog template={templateFixture()} catalog={catalogFixture()} onClose={onClose} onStarted={vi.fn()} />)
    fireEvent.click(screen.getByRole("button", { name: "Run report" }))
    expect(await screen.findByRole("alert")).toHaveTextContent("not available")
    expect(onClose).not.toHaveBeenCalled()
  })
})
