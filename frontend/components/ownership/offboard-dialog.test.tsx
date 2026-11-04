import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-error"

import type { OffboardingPreviewDto } from "@/lib/ownership/types"

const mocks = vi.hoisted(() => ({
  preview: { data: undefined as unknown, isPending: false, isError: false, error: null as unknown, refetch: vi.fn() },
  offboard: vi.fn(),
  transferBulk: vi.fn(),
}))

const toastError = vi.hoisted(() => vi.fn())
vi.mock("sonner", () => ({ toast: { error: toastError, success: vi.fn() } }))
vi.mock("@/lib/ownership/use-ownership", () => ({ useOffboardingPreview: () => mocks.preview }))
vi.mock("@/lib/ownership/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/ownership/api")>()),
  offboardUser: mocks.offboard,
  transferBulk: mocks.transferBulk,
}))
vi.mock("@/components/workflow/person-picker", () => ({
  PersonPicker: ({ value, onChange }: { value: unknown; onChange: (p: unknown) => void }) =>
    value ? (
      <p>Picked Saman</p>
    ) : (
      <button type="button" onClick={() => onChange({ userId: "u-saman", displayName: "Saman", username: "saman", departmentName: null, hasDocumentAccess: null })}>
        pick person
      </button>
    ),
}))

import { OffboardDialog } from "./offboard-dialog"

function previewOf(overrides: Partial<OffboardingPreviewDto> = {}): OffboardingPreviewDto {
  return {
    user: { userId: "u-nimal", name: "Nimal", status: "active" },
    department: null,
    ownedTotal: 7,
    visibleCount: 7,
    hiddenCount: 0,
    inRecycleBinCount: 2,
    suggestedTarget: { userId: "u-admin", name: "Admin", source: "tenant_admin", department: null },
    flags: {
      openTasks: [
        { taskId: "t1", instanceId: "i1", documentId: "doc-1", documentTitle: "Supplier contract", stageName: "Review", status: "pending", route: "assigned", dueAt: null, instanceUrl: "/workflows/instances/i1", documentUrl: "/documents/doc-1", canReassign: true },
        { taskId: "t2", instanceId: "i2", documentId: null, documentTitle: null, stageName: "Sign-off", status: "claimed", route: "claimed", dueAt: null, instanceUrl: "/workflows/instances/i2", documentUrl: null, canReassign: true },
      ],
      startedRuns: [{ instanceId: "i3", documentId: "doc-3", documentTitle: "Leaver's invoice", startedAt: "2026-10-20T00:00:00Z", instanceUrl: "/workflows/instances/i3", canCancel: true }],
      stuckTaskCount: 2,
      startedRunCount: 1,
    },
    canOffboard: true,
    blockers: [],
    ...overrides,
  }
}

function renderDialog(onDone = vi.fn()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <OffboardDialog user={{ userId: "u-nimal", name: "Nimal" }} open onOpenChange={vi.fn()} onDone={onDone} />
    </QueryClientProvider>
  )
  return onDone
}

const confirm = () => screen.getByRole("button", { name: "Offboard user" })
const reason = (text = "Left the company") => fireEvent.change(screen.getByLabelText("Reason"), { target: { value: text } })

describe("OffboardDialog", () => {
  beforeEach(() => {
    mocks.preview.data = previewOf()
    mocks.preview.isPending = false
    mocks.preview.isError = false
    mocks.preview.refetch.mockReset()
    mocks.offboard.mockReset()
    mocks.transferBulk.mockReset()
  })

  it("shows the counts, the flagged tasks and started runs with their links", () => {
    renderDialog()
    expect(screen.getByText("Documents owned").nextSibling).toHaveTextContent("7")
    expect(screen.getByText("In the Recycle Bin").nextSibling).toHaveTextContent("2")
    const tasks = screen.getByRole("list", { name: "Open approval tasks" })
    expect(tasks).toHaveTextContent("Supplier contract")
    expect(tasks).toHaveTextContent("A document you can't open")
    expect(screen.getByRole("link", { name: "Reassign" })).toHaveAttribute("href", "/documents/doc-1")
    expect(screen.getAllByRole("link", { name: "Open run" })[0]).toHaveAttribute("href", "/workflows/instances/i1")
    expect(screen.getByRole("list", { name: "Approvals they started" })).toHaveTextContent("Leaver's invoice")
  })

  it("keeps Offboard disabled until there is a reason, a new owner and the stuck tasks are acknowledged", () => {
    renderDialog()
    expect(confirm()).toBeDisabled()
    reason()
    expect(confirm()).toBeDisabled()
    fireEvent.click(screen.getByRole("button", { name: "pick person" }))
    expect(confirm()).toBeDisabled()                                   // tasks not acknowledged yet
    fireEvent.click(screen.getByRole("checkbox", { name: /I understand 2 tasks will stay stuck/ }))
    expect(confirm()).toBeEnabled()
    reason("   ")
    expect(confirm()).toBeDisabled()                                   // a blank reason doesn't count
  })

  it("sends the acknowledged number with the offboarding", async () => {
    mocks.offboard.mockResolvedValue({ batch: { transferredCount: 7 }, flags: previewOf().flags, deactivated: true })
    const onDone = renderDialog()
    reason()
    fireEvent.click(screen.getByRole("button", { name: "pick person" }))
    fireEvent.click(screen.getByRole("checkbox", { name: /I understand 2 tasks will stay stuck/ }))
    fireEvent.click(confirm())

    await waitFor(() => expect(mocks.offboard).toHaveBeenCalled())
    expect(mocks.offboard).toHaveBeenCalledWith("u-nimal", {
      reason: "Left the company", mode: "transfer", newOwnerUserId: "u-saman", grantAccess: true, acknowledgedStuckTasks: 2,
    })
    expect(mocks.transferBulk).not.toHaveBeenCalled()
    await waitFor(() => expect(onDone).toHaveBeenCalled())
  })

  it("needs no acknowledgement when nothing is stuck, and offers the holding owner", async () => {
    mocks.preview.data = previewOf({ flags: { openTasks: [], startedRuns: [], stuckTaskCount: 0, startedRunCount: 0 } })
    mocks.offboard.mockResolvedValue({ batch: {}, flags: {}, deactivated: true })
    renderDialog()
    expect(screen.queryByRole("checkbox", { name: /I understand/ })).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText("What happens to their documents"), { target: { value: "holding" } })
    expect(screen.getByText(/held by Admin \(the tenant administrator\)/)).toBeInTheDocument()
    reason()
    fireEvent.click(confirm())
    await waitFor(() => expect(mocks.offboard).toHaveBeenCalled())
    expect(mocks.offboard.mock.calls[0][1]).toMatchObject({ mode: "holding", acknowledgedStuckTasks: 0 })
    expect(mocks.offboard.mock.calls[0][1].newOwnerUserId).toBeUndefined()
  })

  it("moves more than 5,000 documents in steps before offboarding", async () => {
    mocks.preview.data = previewOf({ ownedTotal: 11000, visibleCount: 11000, flags: { openTasks: [], startedRuns: [], stuckTaskCount: 0, startedRunCount: 0 } })
    mocks.transferBulk.mockResolvedValue({ transferredCount: 5000 })
    mocks.offboard.mockResolvedValue({ batch: {}, flags: {}, deactivated: true })
    renderDialog()
    expect(screen.getByText(/moved in steps first/)).toBeInTheDocument()
    reason()
    fireEvent.click(screen.getByRole("button", { name: "pick person" }))
    fireEvent.click(confirm())

    await waitFor(() => expect(mocks.offboard).toHaveBeenCalled())
    expect(mocks.transferBulk).toHaveBeenCalledTimes(2)              // 11,000 -> 6,000 -> 1,000, then the offboarding moves the rest
    expect(mocks.transferBulk.mock.calls[0][0]).toMatchObject({ sourceUserId: "u-nimal", mode: "all", limit: 5000 })
  })

  it("asks again with the fresh number when the tasks changed in the meantime", async () => {
    mocks.offboard.mockRejectedValue(new ApiError(409, "Confirm", "OFFBOARD_CONFIRMATION_REQUIRED", { stuckTaskCount: 3 }))
    renderDialog()
    reason()
    fireEvent.click(screen.getByRole("button", { name: "pick person" }))
    fireEvent.click(screen.getByRole("checkbox", { name: /I understand 2 tasks will stay stuck/ }))
    fireEvent.click(confirm())

    await waitFor(() => expect(toastError).toHaveBeenCalledWith(expect.stringContaining("now 3"), expect.anything()))
    expect(mocks.preview.refetch).toHaveBeenCalled()
    expect(screen.getByRole("checkbox", { name: /I understand/ })).toHaveAttribute("aria-checked", "false")
  })

  it("lists the blockers and disables the action", () => {
    mocks.preview.data = previewOf({ canOffboard: false, blockers: ["TENANT_ADMIN_ACCOUNT"] })
    renderDialog()
    expect(screen.getByRole("alert")).toHaveTextContent("tenant administrator account can't be offboarded")
    reason()
    fireEvent.click(screen.getByRole("button", { name: "pick person" }))
    fireEvent.click(screen.getByRole("checkbox", { name: /I understand/ }))
    expect(confirm()).toBeDisabled()
  })
})
