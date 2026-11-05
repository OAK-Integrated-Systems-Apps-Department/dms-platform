import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import type { RetentionViewDto } from "@/lib/retention/api"

import { DocumentRetentionPanel } from "./document-retention-panel"

let view: RetentionViewDto
let granted: string[] = []
const changeStatus = vi.fn()
const extend = vi.fn()
const dispose = vi.fn()

vi.mock("@/lib/retention/use-retention", () => ({
  useDocumentRetention: () => ({ data: view, isPending: false, isError: false }),
  useChangeStatus: () => ({ mutateAsync: changeStatus, isPending: false }),
  useReviewActions: () => ({ extend: { mutateAsync: extend, isPending: false }, dispose: { mutateAsync: dispose, isPending: false } }),
}))
vi.mock("@/lib/auth/use-permissions", () => ({
  usePermissions: () => ({ hasPermission: (code: string) => granted.includes(code) }),
}))

const MARK_ACTIVE = "crm.change-document-status.mark-active"
const MARK_INACTIVE = "crm.change-document-status.mark-inactive"
const REVIEW = "crm.review-retention"

function state(overrides: Partial<RetentionViewDto> = {}): RetentionViewDto {
  return {
    lifecycleState: "active",
    changeSource: null,
    changeReason: null,
    changedAt: null,
    changedByName: null,
    legalHold: false,
    legalHoldReason: null,
    policyId: "p1",
    policyName: "Invoices",
    framework: "HMRC",
    activePeriod: "1 years",
    archivePeriod: "6 years",
    archiveDueAt: "2027-01-31T09:00:00Z",
    reviewDueAt: "2033-01-31T09:00:00Z",
    reviewFlaggedAt: null,
    extendedUntil: null,
    needsPolicy: false,
    automationEnabled: true,
    ...overrides,
  }
}

describe("DocumentRetentionPanel", () => {
  beforeEach(() => {
    changeStatus.mockReset().mockResolvedValue(undefined)
    extend.mockReset().mockResolvedValue(undefined)
    dispose.mockReset().mockResolvedValue(undefined)
    view = state()
    granted = []
  })

  it("shows the status, the rule that applies and when it will be archived and reviewed", () => {
    render(<DocumentRetentionPanel documentId="d1" />)
    expect(screen.getByText("Active")).toBeInTheDocument()
    expect(screen.getByText("Invoices")).toBeInTheDocument()
    expect(screen.getByText(/HMRC/)).toBeInTheDocument()
    expect(screen.getByText("1 year")).toBeInTheDocument()
    expect(screen.getByText("6 years")).toBeInTheDocument()
    expect(screen.getByText("Archives on")).toBeInTheDocument()
    expect(screen.getByText("Reviewed on")).toBeInTheDocument()
  })

  it("offers Mark inactive only with that permission", () => {
    const { unmount } = render(<DocumentRetentionPanel documentId="d1" />)
    expect(screen.queryByRole("button", { name: "Mark inactive" })).not.toBeInTheDocument()
    unmount()
    granted = [MARK_INACTIVE]
    render(<DocumentRetentionPanel documentId="d1" />)
    expect(screen.getByRole("button", { name: "Mark inactive" })).toBeInTheDocument()
  })

  it("marking inactive asks for an optional reason and sends exactly that", async () => {
    granted = [MARK_INACTIVE]
    render(<DocumentRetentionPanel documentId="d1" />)
    fireEvent.click(screen.getByRole("button", { name: "Mark inactive" }))

    expect(screen.getByText("Reason (optional)")).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText("Reason (optional)"), { target: { value: "Invoice paid" } })
    fireEvent.click(screen.getAllByRole("button", { name: "Mark inactive" }).at(-1) as HTMLElement)

    await waitFor(() => expect(changeStatus).toHaveBeenCalledWith({ documentId: "d1", status: "inactive", reason: "Invoice paid" }))
  })

  it("an inactive or archived document can be brought back - with the right words", () => {
    granted = [MARK_ACTIVE]
    view = state({ lifecycleState: "archived", changeSource: "retention", changedAt: "2026-11-01T09:00:00Z" })
    render(<DocumentRetentionPanel documentId="d1" />)
    expect(screen.getByRole("button", { name: "Bring back to Active" })).toBeInTheDocument()
    expect(screen.getByText(/Archived by the retention schedule/)).toBeInTheDocument()
    expect(screen.queryByText("Archives on")).not.toBeInTheDocument() // already archived
  })

  it("under legal hold the switch is disabled, and the panel says the hold paused archiving", () => {
    granted = [MARK_INACTIVE]
    view = state({ legalHold: true, legalHoldReason: "Supplier litigation" })
    render(<DocumentRetentionPanel documentId="d1" />)
    expect(screen.getByRole("button", { name: "Mark inactive" })).toBeDisabled()
    expect(screen.getByText(/Under legal hold \(Supplier litigation\)/)).toBeInTheDocument()
    expect(screen.getByText(/paused by the hold/)).toBeInTheDocument()
  })

  it("says so when no rule covers the document", () => {
    view = state({ policyName: null, framework: null, activePeriod: null, archivePeriod: null, archiveDueAt: null, reviewDueAt: null, needsPolicy: true })
    render(<DocumentRetentionPanel documentId="d1" />)
    expect(screen.getByText(/No retention rule covers this document yet/)).toBeInTheDocument()
  })

  it("a flagged document shows the review box - decisions only for a reviewer, and nothing is deleted by the wording", () => {
    view = state({ reviewFlaggedAt: "2026-11-02T09:00:00Z" })
    const { unmount } = render(<DocumentRetentionPanel documentId="d1" />)
    expect(screen.getByText(/waiting for an administrator/)).toBeInTheDocument()
    expect(screen.getByText(/Nothing has been deleted/)).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: /Keep longer/ })).not.toBeInTheDocument()
    unmount()

    granted = [REVIEW]
    render(<DocumentRetentionPanel documentId="d1" />)
    expect(screen.getByRole("button", { name: /Keep longer/ })).toBeInTheDocument()
    expect(screen.getByRole("button", { name: /Send to recycle bin/ })).toBeInTheDocument()
  })

  it("keeping a document longer needs a reason and sends the chosen length", async () => {
    granted = [REVIEW]
    view = state({ reviewFlaggedAt: "2026-11-02T09:00:00Z" })
    render(<DocumentRetentionPanel documentId="d1" />)
    fireEvent.change(screen.getByLabelText("Keep longer by"), { target: { value: "3" } })
    fireEvent.change(screen.getByLabelText("Extension unit"), { target: { value: "months" } })
    fireEvent.click(screen.getByRole("button", { name: /Keep longer/ }))

    expect(screen.getByText("Reason (required)")).toBeInTheDocument()
    expect(screen.getByRole("button", { name: "Extend retention" })).toBeDisabled()
    fireEvent.change(screen.getByLabelText("Reason (required)"), { target: { value: "Dispute unresolved" } })
    fireEvent.click(screen.getByRole("button", { name: "Extend retention" }))

    await waitFor(() => expect(extend).toHaveBeenCalledWith({ documentId: "d1", amount: 3, unit: "months", reason: "Dispute unresolved" }))
  })

  it("sending to the recycle bin needs a reason too", async () => {
    granted = [REVIEW]
    view = state({ reviewFlaggedAt: "2026-11-02T09:00:00Z" })
    render(<DocumentRetentionPanel documentId="d1" />)
    fireEvent.click(screen.getByRole("button", { name: /Send to recycle bin/ }))
    const confirm = screen.getAllByRole("button", { name: "Send to recycle bin" }).at(-1) as HTMLElement
    expect(confirm).toBeDisabled()
    fireEvent.change(screen.getByLabelText("Reason (required)"), { target: { value: "Retention ended" } })
    fireEvent.click(confirm)
    await waitFor(() => expect(dispose).toHaveBeenCalledWith({ documentId: "d1", reason: "Retention ended" }))
  })
})
