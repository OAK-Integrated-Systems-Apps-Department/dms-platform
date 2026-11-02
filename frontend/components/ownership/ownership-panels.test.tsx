import { fireEvent, render, screen } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import { PermissionCodes } from "@/lib/auth/permission-codes"

const state = vi.hoisted(() => ({
  permissions: new Set<string>(),
  info: undefined as unknown,
  history: { data: undefined as unknown, isPending: false, isError: false },
}))

vi.mock("@/lib/auth/use-permissions", () => ({
  usePermissions: () => ({ hasPermission: (code: string) => state.permissions.has(code), isLoading: false }),
}))
vi.mock("@/lib/ownership/use-ownership", () => ({
  useDocumentOwnership: () => ({ data: state.info }),
  useOwnershipHistory: () => state.history,
}))
vi.mock("@tanstack/react-query", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@tanstack/react-query")>()),
  useQueryClient: () => ({ invalidateQueries: vi.fn() }),
}))
vi.mock("@/components/ownership/transfer-dialog", () => ({
  TransferDialog: () => <div>transfer dialog open</div>,
}))

import { OwnerField } from "./owner-field"
import { OwnershipHistoryPanel } from "./ownership-history-panel"

describe("OwnerField", () => {
  beforeEach(() => {
    state.permissions = new Set([PermissionCodes.DOT_TRANSFER_INITIATE])
    state.info = {
      documentId: "doc-1",
      owner: { userId: "u1", name: "Chamath", status: "active" },
      ownerDepartment: { departmentId: "d1", name: "Finance" },
      ownershipState: "assigned",
      canTransfer: true,
    }
  })

  it("shows the owner, their department and a Transfer button, which opens the dialog", () => {
    render(<OwnerField documentId="doc-1" title="Master agreement" />)
    expect(screen.getByText("Owner")).toBeInTheDocument()
    expect(screen.getByText("Chamath")).toBeInTheDocument()
    expect(screen.getByText(/Finance/)).toBeInTheDocument()
    expect(screen.queryByText("Holding")).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole("button", { name: "Transfer" }))
    expect(screen.getByText("transfer dialog open")).toBeInTheDocument()
  })

  it("flags a document waiting for a new owner and a deactivated owner", () => {
    state.info = { ...(state.info as object), ownershipState: "holding", owner: { userId: "u1", name: "Keeper", status: "deactivated" } }
    render(<OwnerField documentId="doc-1" title="Parked" />)
    expect(screen.getByText("Holding")).toBeInTheDocument()
    expect(screen.getByText("Deactivated")).toBeInTheDocument()
  })

  it("hides Transfer without the permission or when the server says the caller may not", () => {
    state.permissions = new Set()
    const { unmount } = render(<OwnerField documentId="doc-1" title="x" />)
    expect(screen.queryByRole("button", { name: "Transfer" })).not.toBeInTheDocument()
    unmount()
    state.permissions = new Set([PermissionCodes.DOT_TRANSFER_INITIATE])
    state.info = { ...(state.info as object), canTransfer: false }
    render(<OwnerField documentId="doc-1" title="x" />)
    expect(screen.queryByRole("button", { name: "Transfer" })).not.toBeInTheDocument()
  })

  it("renders nothing while the owner can't be loaded", () => {
    state.info = undefined
    const { container } = render(<OwnerField documentId="doc-1" title="x" />)
    expect(container).toBeEmptyDOMElement()
  })
})

describe("OwnershipHistoryPanel", () => {
  beforeEach(() => {
    state.permissions = new Set([PermissionCodes.DOT_HISTORY_VIEW])
    state.history = {
      isPending: false,
      isError: false,
      data: {
        page: 0,
        size: 10,
        total: 2,
        items: [
          { transferId: "t2", transferredAt: "2026-10-20T10:00:00Z", reason: "offboarding", batchId: "b", note: "Left the company", accessGranted: true,
            previousOwner: { userId: "u2", name: "Saman", status: "deactivated" }, previousDepartment: { departmentId: "d1", name: "Finance" },
            newOwner: { userId: "u3", name: "Dilani", status: "active" }, newDepartment: null, actor: { userId: "u9", name: "Admin", status: "active" } },
          { transferId: "t1", transferredAt: "2026-10-01T10:00:00Z", reason: "manual", batchId: null, note: null, accessGranted: false,
            previousOwner: { userId: "u1", name: "Gone Person", status: null }, previousDepartment: null,
            newOwner: { userId: "u2", name: "Saman", status: "active" }, newDepartment: null, actor: null },
        ],
      },
    }
  })

  it("lists each change with the previous owner, even for people who are gone", () => {
    render(<OwnershipHistoryPanel documentId="doc-1" />)
    expect(screen.getByText("Ownership History")).toBeInTheDocument()
    expect(screen.getAllByText(/no longer active/)).toHaveLength(2)    // deactivated Saman, purged Gone Person
    expect(screen.getByText("Gone Person")).toBeInTheDocument()
    expect(screen.getByText("Offboarding")).toBeInTheDocument()
    expect(screen.getByText("Access granted")).toBeInTheDocument()
    expect(screen.getByText("Left the company")).toBeInTheDocument()
    expect(screen.getByText(/by Admin/)).toBeInTheDocument()
  })

  it("needs the history permission", () => {
    state.permissions = new Set()
    const { container } = render(<OwnershipHistoryPanel documentId="doc-1" />)
    expect(container).toBeEmptyDOMElement()
  })

  it("says so when nobody has taken the document over yet", () => {
    state.history = { isPending: false, isError: false, data: { page: 0, size: 10, total: 0, items: [] } }
    render(<OwnershipHistoryPanel documentId="doc-1" />)
    expect(screen.getByText(/Nobody has taken over/)).toBeInTheDocument()
  })
})
