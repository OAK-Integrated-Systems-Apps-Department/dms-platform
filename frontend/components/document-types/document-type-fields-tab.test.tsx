import "@testing-library/jest-dom/vitest"
import { useState } from "react"
import { render, screen, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { describe, expect, it, vi } from "vitest"

import type { DocumentTypeFieldResponseDto } from "@/lib/document-type-fields/api"
import type { MetadataFieldResponseDto } from "@/lib/metadata-fields/api"

import { DocumentTypeFieldsTab } from "./document-type-fields-tab"

vi.mock("sonner", () => ({ toast: { success: vi.fn(), error: vi.fn() } }))

function mapped(overrides: Partial<DocumentTypeFieldResponseDto>): DocumentTypeFieldResponseDto {
  return {
    documentTypeFieldId: `dtf-${overrides.fieldId ?? "f1"}`,
    documentTypeId: "dt-po",
    fieldId: "f1",
    fieldKey: "po_number",
    label: "PO Number",
    schemaVersion: 3,
    status: "active",
    mandatory: false,
    hidden: false,
    requiresBackfill: false,
    displayOrder: 10,
    fieldGroup: null,
    defaultValueOverride: null,
    archivedAt: null,
    labelAliases: [],
    ...overrides,
  }
}

function catalogue(overrides: Partial<MetadataFieldResponseDto>): MetadataFieldResponseDto {
  return {
    fieldId: "f1",
    tenantId: "t1",
    fieldKey: "po_number",
    label: "PO Number",
    helpText: null,
    dataType: "text",
    defaultValue: null,
    systemField: false,
    searchable: true,
    ocrExtractable: true,
    status: "active",
    labelAliases: [],
    createdAt: "2026-09-01T00:00:00Z",
    updatedAt: null,
    ...overrides,
  }
}

const PO_NUMBER = mapped({ fieldId: "f1", fieldKey: "po_number", label: "PO Number", displayOrder: 10 })
const INVOICE_NUMBER = mapped({ fieldId: "f2", fieldKey: "invoice_number", label: "Invoice Number", displayOrder: 20 })

function renderTab(props: Partial<React.ComponentProps<typeof DocumentTypeFieldsTab>> = {}) {
  const onPublish = vi.fn(async () => null)
  const onSaveLabelHints = vi.fn(async () => null)
  const view = render(
    <DocumentTypeFieldsTab
      documentTypeName="Purchase Order"
      currentFields={[PO_NUMBER, INVOICE_NUMBER]}
      availableFields={[catalogue({ fieldId: "f1" }), catalogue({ fieldId: "f2", fieldKey: "invoice_number", label: "Invoice Number" })]}
      canUpdate
      onPublish={onPublish}
      onSaveLabelHints={onSaveLabelHints}
      {...props}
    />
  )
  return { ...view, onPublish, onSaveLabelHints }
}

const poToggle = () => screen.getByRole("button", { name: /^Hints for this type — PO Number/ })
const poHintsInput = () => screen.getByLabelText("Hints for this type — PO Number", { selector: "input" })

describe("DocumentTypeFieldsTab — Hints for this type", () => {
  it("gives every row's toggle a distinct accessible name, including its saved-hint count", () => {
    renderTab({ currentFields: [mapped({ ...PO_NUMBER, labelAliases: ["Number", "PO No"] }), INVOICE_NUMBER] })
    expect(screen.getAllByRole("button", { name: /^Hints for this type/ })).toHaveLength(2)
    expect(screen.getByRole("button", { name: "Hints for this type — PO Number, 2 hints" })).toBeInTheDocument()
    expect(screen.getByRole("button", { name: "Hints for this type — Invoice Number" })).toBeInTheDocument()
  })

  it("keeps unsaved chips (and refused text) when the row is collapsed and expanded again", async () => {
    const user = userEvent.setup()
    renderTab()
    await user.click(poToggle())
    await user.type(poHintsInput(), "Number{Enter}x")
    await user.click(poToggle()) // collapse — the blur refuses "x", which stays in the input
    expect(poToggle()).toHaveAccessibleName("Hints for this type — PO Number, unsaved changes")
    await user.click(poToggle()) // expand
    expect(screen.getByRole("button", { name: "Remove Number" })).toBeInTheDocument()
    expect(poHintsInput()).toHaveValue("x")
  })

  it("warns next to Publish about unsaved hints, and Publish neither saves nor clears them", async () => {
    const user = userEvent.setup()
    const { onPublish, onSaveLabelHints } = renderTab()
    const publish = screen.getByRole("button", { name: "Publish New Version" })
    expect(publish).not.toHaveAccessibleDescription()

    await user.click(poToggle())
    await user.type(poHintsInput(), "Number{Enter}")
    expect(publish).toHaveAccessibleDescription(/Unsaved hints for this type on PO Number\. Publishing doesn.t save hints/)

    await user.click(publish)
    expect(onPublish).toHaveBeenCalledTimes(1)
    expect(onSaveLabelHints).not.toHaveBeenCalled()
    // Still unsaved after the publish, so the warning stays.
    expect(publish).toHaveAccessibleDescription(/PO Number/)
    expect(screen.getByRole("button", { name: "Remove Number" })).toBeInTheDocument()
  })

  it("saves the draft through onSaveLabelHints, then drops it in favour of the saved list", async () => {
    const user = userEvent.setup()
    const saved: [string, string[]][] = []
    // Stands in for the detail page + useDocumentType, which patch the saved
    // list into the mapping before the save resolves.
    function Harness() {
      const [fields, setFields] = useState([PO_NUMBER, INVOICE_NUMBER])
      return (
        <DocumentTypeFieldsTab
          documentTypeName="Purchase Order"
          currentFields={fields}
          availableFields={[catalogue({ fieldId: "f1" })]}
          canUpdate
          onPublish={async () => null}
          onSaveLabelHints={async (fieldId, labelAliases) => {
            saved.push([fieldId, labelAliases])
            setFields((prev) => prev.map((f) => (f.fieldId === fieldId ? { ...f, labelAliases } : f)))
            return null
          }}
        />
      )
    }
    render(<Harness />)

    await user.click(poToggle())
    await user.type(poHintsInput(), "Number{Enter}")
    await user.click(screen.getByRole("button", { name: "Save hints" }))

    expect(saved).toEqual([["f1", ["Number"]]])
    expect(poToggle()).toHaveAccessibleName("Hints for this type — PO Number, 1 hint")
    expect(screen.queryByText(/Unsaved hints for this type/)).not.toBeInTheDocument()
    expect(screen.getByRole("button", { name: "Remove Number" })).toBeInTheDocument()
    expect(screen.getByRole("button", { name: "Save hints" })).toBeDisabled()
  })

  it("won't save while refused text is still in the input", async () => {
    const user = userEvent.setup()
    const { onSaveLabelHints } = renderTab()
    await user.click(poToggle())
    await user.type(poHintsInput(), "x")
    await user.click(screen.getByRole("button", { name: "Save hints" }))
    expect(onSaveLabelHints).not.toHaveBeenCalled()
    expect(within(screen.getByRole("alert")).getByText(/at least 2/)).toBeInTheDocument()
  })
})
