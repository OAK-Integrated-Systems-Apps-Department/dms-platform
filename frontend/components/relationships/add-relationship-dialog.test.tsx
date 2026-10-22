import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-error"

import type { RelationshipTypeDto } from "@/lib/relationships/types"

const mocks = vi.hoisted(() => ({
  create: vi.fn(),
  bulk: vi.fn(),
  types: [] as unknown[],
}))

vi.mock("@/lib/relationships/use-document-relationships", () => ({
  useCreateRelationship: () => ({ mutateAsync: mocks.create }),
  useCreateRelationshipsBulk: () => ({ mutateAsync: mocks.bulk }),
}))
vi.mock("@/lib/relationships/use-relationship-types", () => ({
  useEnabledRelationshipTypes: () => ({ data: mocks.types, isError: false }),
}))
// The real picker searches the server; here it offers two documents to pick.
vi.mock("@/components/workflow/document-picker", () => ({
  DocumentPicker: ({ onPick, pickedIds }: { onPick: (d: unknown) => void; pickedIds: string[] }) => (
    <div>
      {["doc-2", "doc-3"].filter((id) => !pickedIds.includes(id)).map((id) => (
        <button key={id} type="button" onClick={() => onPick({ documentId: id, title: `Title ${id}` })}>
          pick {id}
        </button>
      ))}
    </div>
  ),
}))

import { AddRelationshipDialog } from "./add-relationship-dialog"

function type(overrides: Partial<RelationshipTypeDto>): RelationshipTypeDto {
  return {
    relationshipTypeId: "t-sup",
    code: "SUPERSEDES",
    category: "versioning",
    label: "Supersedes",
    inverseLabel: "Superseded by",
    directionality: "directional",
    preventsCycles: true,
    system: true,
    enabled: true,
    displayOrder: 40,
    usageCount: 0,
    ...overrides,
  }
}

function open() {
  render(<AddRelationshipDialog documentId="doc-1" linkedIds={[]} open onOpenChange={vi.fn()} />)
}

describe("AddRelationshipDialog", () => {
  beforeEach(() => {
    mocks.create.mockReset()
    mocks.bulk.mockReset()
    mocks.types = [type({}), type({ relationshipTypeId: "t-rel", code: "RELATED", label: "Related document", inverseLabel: "Related document", directionality: "symmetric", preventsCycles: false })]
  })

  it("offers only the types the server returned (the enabled ones)", () => {
    open()
    const select = screen.getByLabelText("Relationship type")
    expect(select).toHaveTextContent("Supersedes / Superseded by")
    expect(select).toHaveTextContent("Related document")
    expect(select).not.toHaveTextContent("Replaces")
  })

  it("asks which way a one-way type reads and sends a single link", async () => {
    mocks.create.mockResolvedValue({})
    open()
    fireEvent.change(screen.getByLabelText("Relationship type"), { target: { value: "t-sup" } })
    fireEvent.click(screen.getByLabelText(/is superseded by the chosen one/))
    fireEvent.click(screen.getByRole("button", { name: "pick doc-2" }))
    fireEvent.click(screen.getByRole("button", { name: "Link" }))

    await waitFor(() => expect(mocks.create).toHaveBeenCalled())
    expect(mocks.create).toHaveBeenCalledWith({
      relationshipTypeId: "t-sup",
      sourceDocumentId: "doc-2",
      targetDocumentId: "doc-1",
      note: undefined,
    })
    expect(mocks.bulk).not.toHaveBeenCalled()
  })

  it("uses the bulk endpoint for several documents", async () => {
    mocks.bulk.mockResolvedValue({})
    open()
    fireEvent.change(screen.getByLabelText("Relationship type"), { target: { value: "t-rel" } })
    fireEvent.click(screen.getByRole("button", { name: "pick doc-2" }))
    fireEvent.click(screen.getByRole("button", { name: "pick doc-3" }))
    fireEvent.click(screen.getByRole("button", { name: "Link 2 documents" }))

    await waitFor(() => expect(mocks.bulk).toHaveBeenCalled())
    expect(mocks.bulk).toHaveBeenCalledWith({
      relationshipTypeId: "t-rel",
      sourceDocumentIds: ["doc-1"],
      targetDocumentIds: ["doc-2", "doc-3"],
      note: undefined,
    })
  })

  it("shows the message and the chain of a loop the server refused", async () => {
    mocks.create.mockRejectedValue(
      new ApiError(409, "This would create a loop: A → B → A. Remove one of those links first.", "RELATIONSHIP_CYCLE", {
        chain: [{ documentId: "doc-2", title: "Policy B" }, { title: "A document you can't open" }, { documentId: "doc-1", title: "Policy A" }],
      })
    )
    open()
    fireEvent.change(screen.getByLabelText("Relationship type"), { target: { value: "t-sup" } })
    fireEvent.click(screen.getByRole("button", { name: "pick doc-2" }))
    fireEvent.click(screen.getByRole("button", { name: "Link" }))

    expect(await screen.findByText(/This would create a loop/)).toBeInTheDocument()
    const chain = screen.getByRole("list", { name: "Loop chain" })
    expect(chain).toHaveTextContent("Policy B")
    expect(chain).toHaveTextContent("A document you can't open")
    expect(chain).toHaveTextContent("Policy A")
  })

  it("lists each document a bulk request was refused for", async () => {
    mocks.bulk.mockRejectedValue(
      new ApiError(422, "1 of 2 links can't be created; nothing was saved.", "RELATIONSHIP_BULK_INVALID", [
        { documentId: "doc-3", code: "DUPLICATE", message: "These documents are already linked this way." },
      ])
    )
    open()
    fireEvent.change(screen.getByLabelText("Relationship type"), { target: { value: "t-rel" } })
    fireEvent.click(screen.getByRole("button", { name: "pick doc-2" }))
    fireEvent.click(screen.getByRole("button", { name: "pick doc-3" }))
    fireEvent.click(screen.getByRole("button", { name: "Link 2 documents" }))

    expect(await screen.findByText(/nothing was saved/)).toBeInTheDocument()
    const list = screen.getByRole("list", { name: "Documents that can't be linked" })
    expect(list).toHaveTextContent("Title doc-3")
    expect(list).toHaveTextContent("These documents are already linked this way.")
  })
})
