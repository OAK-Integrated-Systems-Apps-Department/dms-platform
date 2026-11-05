import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { beforeEach, describe, expect, it, vi } from "vitest"

import type { BatchSummaryDto } from "@/lib/ownership/types"

const mocks = vi.hoisted(() => ({
  previewDoc: vi.fn(),
  previewBulk: vi.fn(),
  transferDoc: vi.fn(),
  transferBulk: vi.fn(),
  transferAll: vi.fn(),
}))

const toastError = vi.hoisted(() => vi.fn())
vi.mock("sonner", () => ({ toast: { error: toastError, success: vi.fn() } }))
vi.mock("@/lib/ownership/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/ownership/api")>()),
  previewDocumentTransfer: mocks.previewDoc,
  previewBulkTransfer: mocks.previewBulk,
  transferDocument: mocks.transferDoc,
  transferBulk: mocks.transferBulk,
  transferAllInSteps: mocks.transferAll,
}))
vi.mock("@/lib/workflow/use-participant-options", () => ({
  useDepartmentOptions: () => ({ data: [{ value: "d-legal", label: "Legal" }] }),
}))
// The real picker searches the server; here it offers one person.
vi.mock("@/components/workflow/person-picker", () => ({
  PersonPicker: ({ value, onChange }: { value: unknown; onChange: (p: unknown) => void }) =>
    value ? (
      <p>Picked Nimal</p>
    ) : (
      <button type="button" onClick={() => onChange({ userId: "u-new", displayName: "Nimal", username: "nimal", departmentName: null, hasDocumentAccess: null })}>
        pick person
      </button>
    ),
}))

import { TransferDialog } from "./transfer-dialog"

function summary(overrides: Partial<BatchSummaryDto> = {}): BatchSummaryDto {
  return {
    batchId: "b1", kind: "bulk", reason: "manual", totalCount: 3, transferredCount: 3, skippedCount: 0, skipped: [],
    accessGrantedCount: 0, convertedToRestrictedCount: 0, flaggedTaskCount: 0, newOwnerLacksViewPermission: false,
    newOwner: null, newOwnerDepartment: null, holding: false, createdBy: null, createdAt: "2026-10-26T00:00:00Z", completedAt: null,
    ...overrides,
  }
}

function renderDialog(subject: Parameters<typeof TransferDialog>[0]["subject"], onDone = vi.fn()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <TransferDialog subject={subject} open onOpenChange={vi.fn()} onDone={onDone} />
    </QueryClientProvider>
  )
  return onDone
}

const DOCUMENT = { kind: "document", documentId: "doc-1", title: "Master agreement" } as const
const grantBox = () => screen.getByRole("checkbox", { name: /Also give the new owner access/ })

describe("TransferDialog", () => {
  beforeEach(() => {
    Object.values(mocks).forEach((m) => m.mockReset())
    mocks.previewDoc.mockResolvedValue({ willConvertToRestricted: false, newOwnerLacksViewPermission: false, alreadyOwner: false })
  })

  it("starts with 'Also give the new owner access' switched on", () => {
    renderDialog(DOCUMENT)
    expect(grantBox()).toHaveAttribute("aria-checked", "true")
    expect(screen.getByRole("button", { name: "Transfer" })).toBeDisabled()
  })

  it("tells the admin, before confirming, that the document will be switched to Restricted", async () => {
    mocks.previewDoc.mockResolvedValue({ willConvertToRestricted: true, newOwnerLacksViewPermission: false, alreadyOwner: false })
    renderDialog(DOCUMENT)
    fireEvent.click(screen.getByRole("button", { name: "pick person" }))

    const note = await screen.findByRole("note")
    expect(note).toHaveTextContent("1 document will be switched to Restricted")
    expect(note).toHaveTextContent("could not otherwise open")
    expect(mocks.previewDoc).toHaveBeenCalledWith("doc-1", { newOwnerUserId: "u-new", newOwnerDepartmentId: null, grantAccess: true })

    // switching the access option off removes the notice
    fireEvent.click(grantBox())
    await waitFor(() => expect(screen.queryByRole("note")).not.toBeInTheDocument())
  })

  it("shows no notice when nothing would be switched", async () => {
    renderDialog(DOCUMENT)
    fireEvent.click(screen.getByRole("button", { name: "pick person" }))
    await waitFor(() => expect(mocks.previewDoc).toHaveBeenCalled())
    expect(screen.queryByRole("note")).not.toBeInTheDocument()
  })

  it("transfers one document with the access option and reports the result", async () => {
    mocks.transferDoc.mockResolvedValue({ accessConvertedToRestricted: false })
    const onDone = renderDialog(DOCUMENT)
    fireEvent.click(screen.getByRole("button", { name: "pick person" }))
    fireEvent.click(screen.getByRole("button", { name: /^Department/ }))
    fireEvent.click(screen.getByRole("button", { name: "Legal" }))
    fireEvent.click(screen.getByRole("button", { name: "Transfer" }))

    await waitFor(() => expect(mocks.transferDoc).toHaveBeenCalled())
    expect(mocks.transferDoc).toHaveBeenCalledWith("doc-1", {
      newOwnerUserId: "u-new", newOwnerDepartmentId: "d-legal", grantAccess: true, note: undefined,
    })
    await waitFor(() => expect(onDone).toHaveBeenCalledWith({ kind: "document", result: { accessConvertedToRestricted: false } }))
  })

  it("explains the 5,000 split and moves the documents in steps automatically", async () => {
    mocks.previewBulk.mockResolvedValue({ willConvertToRestrictedCount: 12, newOwnerLacksViewPermission: false })
    mocks.transferAll.mockResolvedValue({ done: 12000, batches: [summary({ transferredCount: 5000, totalCount: 5000 }), summary({ transferredCount: 5000, totalCount: 5000 }), summary({ transferredCount: 2000, totalCount: 2000 })] })
    const onDone = renderDialog({ kind: "bulk", sourceUserId: "u-old", sourceLabel: "Owner", mode: "all", total: 12000 })

    expect(screen.getByText(/in steps of 5,000/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole("button", { name: "pick person" }))
    expect(await screen.findByRole("note")).toHaveTextContent("12 documents will be switched to Restricted in the first step")
    // the preview of a set above the limit asks for the first step only
    expect(mocks.previewBulk).toHaveBeenCalledWith(expect.objectContaining({ mode: "all", limit: 5000 }))

    fireEvent.click(screen.getByRole("button", { name: "Transfer" }))
    await waitFor(() => expect(mocks.transferAll).toHaveBeenCalled())
    expect(mocks.transferAll.mock.calls[0][0]).toMatchObject({ sourceUserId: "u-old", grantAccess: true, target: { type: "user", newOwnerUserId: "u-new" } })
    expect(mocks.transferBulk).not.toHaveBeenCalled()
    await waitFor(() => expect(onDone).toHaveBeenCalled())
    expect(onDone.mock.calls[0][0].summary.transferredCount).toBe(12000)
  })

  it("does not mention a split for a small set and sends one request", async () => {
    mocks.previewBulk.mockResolvedValue({ willConvertToRestrictedCount: 0, newOwnerLacksViewPermission: false })
    mocks.transferBulk.mockResolvedValue(summary())
    renderDialog({ kind: "bulk", sourceUserId: "u-old", sourceLabel: "Owner", mode: "selected", documentIds: ["a", "b", "c"], total: 3 })

    expect(screen.queryByText(/in steps of 5,000/)).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole("button", { name: "pick person" }))
    fireEvent.click(screen.getByRole("button", { name: "Transfer" }))
    await waitFor(() => expect(mocks.transferBulk).toHaveBeenCalledTimes(1))
    expect(mocks.transferBulk.mock.calls[0][0]).toMatchObject({ mode: "selected", documentIds: ["a", "b", "c"], grantAccess: true })
  })

  it("lets a bulk transfer go to the holding owner without picking a person", async () => {
    mocks.previewBulk.mockResolvedValue({ willConvertToRestrictedCount: 0, newOwnerLacksViewPermission: false })
    mocks.transferBulk.mockResolvedValue(summary({ holding: true }))
    renderDialog({ kind: "bulk", sourceUserId: "u-old", sourceLabel: "Owner", mode: "all", total: 3 })
    fireEvent.click(screen.getByRole("checkbox", { name: /holding owner instead/ }))
    fireEvent.click(screen.getByRole("button", { name: "Transfer" }))
    await waitFor(() => expect(mocks.transferBulk).toHaveBeenCalled())
    expect(mocks.transferBulk.mock.calls[0][0].target).toMatchObject({ type: "holding" })
  })

  it("shows the server's message when the transfer is refused", async () => {
    mocks.transferDoc.mockRejectedValue(new Error("That person already owns this document."))
    renderDialog(DOCUMENT)
    fireEvent.click(screen.getByRole("button", { name: "pick person" }))
    fireEvent.click(screen.getByRole("button", { name: "Transfer" }))
    await waitFor(() => expect(toastError).toHaveBeenCalledWith(expect.stringContaining("already owns"), expect.anything()))
  })
})
