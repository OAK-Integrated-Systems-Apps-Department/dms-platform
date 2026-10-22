import { fireEvent, render, screen } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import type { RelatedDocumentsDto, RelatedItemDto } from "@/lib/relationships/types"

const state = vi.hoisted(() => ({ data: undefined as unknown, isError: false }))

vi.mock("@/lib/relationships/use-document-relationships", () => ({
  useDocumentRelationships: () => ({
    data: state.data,
    isPending: false,
    isError: state.isError,
    error: null,
  }),
  useRemoveRelationship: () => ({ mutateAsync: vi.fn() }),
  useCreateRelationship: () => ({ mutateAsync: vi.fn() }),
  useCreateRelationshipsBulk: () => ({ mutateAsync: vi.fn() }),
  useUpdateRelationship: () => ({ mutateAsync: vi.fn() }),
}))
vi.mock("@/lib/relationships/use-relationship-types", () => ({
  useEnabledRelationshipTypes: () => ({ data: [], isError: false }),
}))
vi.mock("@/components/workflow/document-picker", () => ({ DocumentPicker: () => null }))

import { RelatedDocumentsPanel } from "./related-documents-panel"

function item(overrides: Partial<RelatedItemDto> = {}): RelatedItemDto {
  return {
    relationshipId: "rel-1",
    direction: "outgoing",
    label: "Supersedes",
    origin: "manual",
    note: null,
    createdAt: "2026-10-01T10:00:00Z",
    createdBy: { userId: "u1", name: "Chamath" },
    canEdit: true,
    canRemove: true,
    other: { accessible: true, documentId: "doc-2", title: "Travel policy 2025", documentTypeName: "Policy", status: "Approved", inRecycleBin: false },
    ...overrides,
  }
}

function panel(overrides: Partial<RelatedDocumentsDto> = {}): RelatedDocumentsDto {
  return {
    documentId: "doc-1",
    total: 1,
    canAdd: true,
    groups: [
      {
        relationshipTypeId: "t-sup",
        typeCode: "SUPERSEDES",
        typeLabel: "Supersedes",
        typeInverseLabel: "Superseded by",
        typeEnabled: true,
        items: [item()],
      },
    ],
    ...overrides,
  }
}

describe("RelatedDocumentsPanel", () => {
  beforeEach(() => {
    state.data = panel()
    state.isError = false
  })

  it("groups links by type and reads each row from this document's side", () => {
    state.data = panel({
      total: 2,
      groups: [
        { relationshipTypeId: "t-sup", typeCode: "SUPERSEDES", typeLabel: "Supersedes", typeInverseLabel: "Superseded by", typeEnabled: true, items: [item(), item({ relationshipId: "rel-2", direction: "incoming", label: "Superseded by", other: { accessible: true, documentId: "doc-3", title: "Travel policy 2027", status: "Draft" } })] },
      ],
    })
    render(<RelatedDocumentsPanel documentId="doc-1" />)

    expect(screen.getByRole("region", { name: "Supersedes" })).toBeInTheDocument()
    expect(screen.getByText("Superseded by", { selector: "span" })).toBeInTheDocument()
    expect(screen.getByRole("link", { name: "Travel policy 2025" })).toHaveAttribute("href", "/documents/doc-2")
    expect(screen.getByText("Policy")).toBeInTheDocument()
    expect(screen.getByText("Approved")).toBeInTheDocument()
    expect(screen.getByRole("link", { name: "Find documents that supersede this" })).toHaveAttribute(
      "href",
      "/documents?relatedTo=doc-1&relationshipType=SUPERSEDES&direction=source"
    )
  })

  it("shows a document the viewer can't open as a locked row with nothing identifying it", () => {
    state.data = panel({
      groups: [
        { relationshipTypeId: "t-rel", typeCode: "RELATED", typeLabel: "Related document", typeInverseLabel: "Related document", typeEnabled: true, items: [item({ label: "Related document", direction: "symmetric", canEdit: false, canRemove: false, other: { accessible: false } })] },
      ],
    })
    const { container } = render(<RelatedDocumentsPanel documentId="doc-1" />)

    expect(screen.getByText("A document you can't open")).toBeInTheDocument()
    expect(screen.queryByRole("link", { name: /A document you can't open/ })).not.toBeInTheDocument()
    expect(container.querySelector('a[href^="/documents/doc"]')).toBeNull()
    expect(screen.queryByRole("button", { name: "Edit relationship" })).not.toBeInTheDocument()
  })

  it("names a document in the Recycle Bin but doesn't link to it", () => {
    state.data = panel({
      groups: [
        { relationshipTypeId: "t-sup", typeCode: "SUPERSEDES", typeLabel: "Supersedes", typeInverseLabel: "Superseded by", typeEnabled: true, items: [item({ other: { accessible: true, documentId: "doc-9", title: "Old memo", inRecycleBin: true } })] },
      ],
    })
    render(<RelatedDocumentsPanel documentId="doc-1" />)

    expect(screen.getByText("Old memo")).toBeInTheDocument()
    expect(screen.getByText("In Recycle Bin")).toBeInTheDocument()
    expect(screen.queryByRole("link", { name: "Old memo" })).not.toBeInTheDocument()
  })

  it("flags a disabled type", () => {
    state.data = panel({
      groups: [{ relationshipTypeId: "t", typeCode: "X", typeLabel: "Replaces", typeInverseLabel: "Replaced by", typeEnabled: false, items: [item()] }],
    })
    render(<RelatedDocumentsPanel documentId="doc-1" />)
    expect(screen.getByText("Disabled type")).toBeInTheDocument()
  })

  it("offers edit, remove and add only as the server allows", () => {
    state.data = panel({
      canAdd: false,
      groups: [
        { relationshipTypeId: "t-sup", typeCode: "SUPERSEDES", typeLabel: "Supersedes", typeInverseLabel: "Superseded by", typeEnabled: true, items: [item({ canEdit: true, canRemove: false })] },
      ],
    })
    render(<RelatedDocumentsPanel documentId="doc-1" />)

    expect(screen.getByRole("button", { name: "Edit relationship" })).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: "Remove relationship" })).not.toBeInTheDocument()
    expect(screen.queryByRole("button", { name: "Add relationship" })).not.toBeInTheDocument()
  })

  it("hides every control when read-only", () => {
    render(<RelatedDocumentsPanel documentId="doc-1" readOnly />)

    expect(screen.getByText("Travel policy 2025")).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: "Edit relationship" })).not.toBeInTheDocument()
    expect(screen.queryByRole("button", { name: "Remove relationship" })).not.toBeInTheDocument()
    expect(screen.queryByRole("button", { name: "Add relationship" })).not.toBeInTheDocument()
  })

  it("shows the add button when the server says the viewer can add", () => {
    render(<RelatedDocumentsPanel documentId="doc-1" />)
    expect(screen.getByRole("button", { name: "Add relationship" })).toBeInTheDocument()
    fireEvent.click(screen.getByRole("button", { name: "Add relationship" }))
    expect(screen.getByText("Link this document to another so they can be found from each other.")).toBeInTheDocument()
  })

  it("renders nothing when read-only and the document can't be loaded", () => {
    state.data = undefined
    state.isError = true
    const { container } = render(<RelatedDocumentsPanel documentId="doc-1" readOnly />)
    expect(container).toBeEmptyDOMElement()
  })

  it("says so when nothing is linked", () => {
    state.data = panel({ total: 0, groups: [] })
    render(<RelatedDocumentsPanel documentId="doc-1" />)
    expect(screen.getByText("No documents are linked to this one yet.")).toBeInTheDocument()
  })
})
