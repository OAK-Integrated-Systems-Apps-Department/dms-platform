import { fireEvent, render, screen } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import type { RunDto } from "@/lib/reports/api"

import { RunHistoryTab } from "./run-history-tab"
import { runFixture } from "./test-fixtures"

let runs: RunDto[] = []
let lastParams: unknown
const download = vi.fn()
const retry = vi.fn()
const cancel = vi.fn()

vi.mock("@/lib/reports/use-reports", () => ({
  useRuns: (params: unknown) => {
    lastParams = params
    return { data: { content: runs, page: 0, size: 20, totalElements: runs.length, totalPages: 1, last: true }, isPending: false, isError: false }
  },
  useDownloadRun: () => ({ mutate: download, isPending: false }),
  useRetryRun: () => ({ mutate: retry, isPending: false }),
  useCancelRun: () => ({ mutate: cancel, isPending: false }),
}))

describe("RunHistoryTab", () => {
  beforeEach(() => {
    runs = []
    download.mockClear()
    retry.mockClear()
    cancel.mockClear()
  })

  it("offers Download only for a file that can still be downloaded", () => {
    runs = [runFixture({ runId: "ready" }), runFixture({ runId: "old", status: "expired", downloadable: false })]
    render(<RunHistoryTab />)
    expect(screen.getAllByRole("button", { name: "Download" })).toHaveLength(1)
    fireEvent.click(screen.getByRole("button", { name: "Download" }))
    expect(download).toHaveBeenCalledWith("ready")
    expect(screen.getByRole("button", { name: "Retry" })).toBeInTheDocument()
  })

  it("lets an open run be cancelled and a failed one be retried, with the reason shown", () => {
    runs = [
      runFixture({ runId: "busy", status: "running", downloadable: false, rowCount: null, progressRows: 5000 }),
      runFixture({ runId: "bad", status: "failed", downloadable: false, errorMessage: "The report timed out." }),
    ]
    render(<RunHistoryTab />)
    expect(screen.getByText("The report timed out.")).toBeInTheDocument()
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }))
    expect(cancel).toHaveBeenCalledWith("busy")
    fireEvent.click(screen.getByRole("button", { name: "Retry" }))
    expect(retry).toHaveBeenCalledWith("bad")
  })

  it("filters by status from the first page", () => {
    runs = [runFixture()]
    render(<RunHistoryTab />)
    fireEvent.change(screen.getByLabelText("Show"), { target: { value: "failed" } })
    expect(lastParams).toEqual({ status: "failed", page: 0 })
  })

  it("says so when there is nothing to show", () => {
    render(<RunHistoryTab />)
    expect(screen.getByText(/No report runs/)).toBeInTheDocument()
  })
})
