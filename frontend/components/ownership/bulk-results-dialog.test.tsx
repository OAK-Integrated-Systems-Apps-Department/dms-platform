import { render, screen } from "@testing-library/react"
import { describe, expect, it, vi } from "vitest"

import { BulkResultsDialog } from "./bulk-results-dialog"

import type { BatchSummaryDto } from "@/lib/ownership/types"

function summary(overrides: Partial<BatchSummaryDto> = {}): BatchSummaryDto {
  return {
    batchId: "b1", kind: "bulk", reason: "manual", totalCount: 10, transferredCount: 8, skippedCount: 2,
    skipped: [{ reason: "purged", count: 1, documentIds: ["x"] }, { reason: "already_owner", count: 1, documentIds: ["y"] }],
    accessGrantedCount: 5, convertedToRestrictedCount: 3, flaggedTaskCount: 0, newOwnerLacksViewPermission: false,
    newOwner: { userId: "u", name: "Nimal", status: "active" }, newOwnerDepartment: { departmentId: "d", name: "Legal" },
    holding: false, createdBy: null, createdAt: "2026-10-26T00:00:00Z", completedAt: null,
    ...overrides,
  }
}

describe("BulkResultsDialog", () => {
  it("reports moved, skipped by reason, access and the documents switched to Restricted", () => {
    render(<BulkResultsDialog summary={summary()} open onOpenChange={vi.fn()} />)
    expect(screen.getByText("8 documents transferred")).toBeInTheDocument()
    expect(screen.getByText("Nimal")).toBeInTheDocument()
    const skipped = screen.getByRole("list", { name: "Skipped documents" })
    expect(skipped).toHaveTextContent("Permanently deleted")
    expect(skipped).toHaveTextContent("Already owned that way")
    expect(screen.getByRole("note")).toHaveTextContent("3 documents were switched to Restricted")
  })

  it("leaves out the Restricted note and the skip list when there is nothing to say, and warns about the view permission", () => {
    render(<BulkResultsDialog summary={summary({ convertedToRestrictedCount: 0, skipped: [], skippedCount: 0, newOwnerLacksViewPermission: true, holding: true })} open onOpenChange={vi.fn()} />)
    expect(screen.queryByRole("note")).not.toBeInTheDocument()
    expect(screen.queryByRole("list", { name: "Skipped documents" })).not.toBeInTheDocument()
    expect(screen.getByText(/permission to view documents/)).toBeInTheDocument()
    expect(screen.getByText("8 documents parked")).toBeInTheDocument()
  })
})
