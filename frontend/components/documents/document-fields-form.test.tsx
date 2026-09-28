import { fireEvent, render, screen } from "@testing-library/react"
import { describe, expect, it, vi } from "vitest"

import { TooltipProvider } from "@/components/ui/tooltip"
import type { DocumentTypeSchemaResponseDto } from "@/lib/document-type-fields/api"

import { FieldChips, defaultRequestedFields, mandatoryRequestedFields, ocrMatchNote, reconcileRequestedFields } from "./document-fields-form"

function field(overrides: Partial<DocumentTypeSchemaResponseDto["fields"][number]>): DocumentTypeSchemaResponseDto["fields"][number] {
  return {
    fieldId: overrides.fieldKey ?? "field-id",
    fieldKey: "field_key",
    label: "Field",
    helpText: null,
    dataType: "text",
    mandatory: false,
    hidden: false,
    ocrExtractable: false,
    displayOrder: 0,
    fieldGroup: null,
    defaultValue: null,
    options: [],
    validations: [],
    ...overrides,
  }
}

function schemaWith(fields: DocumentTypeSchemaResponseDto["fields"]): DocumentTypeSchemaResponseDto {
  return {
    documentTypeId: "doc-type-1",
    typeCode: "GENERAL",
    typeName: "General",
    schemaVersion: 1,
    fields,
  }
}

const CLASSIFICATION = field({ fieldId: "f1", fieldKey: "classification_level", label: "Classification Level", mandatory: true, displayOrder: 10 })
const AUTHOR = field({ fieldId: "f2", fieldKey: "author", label: "Author", displayOrder: 20 })
const TAGS = field({ fieldId: "f3", fieldKey: "tags", label: "Tags", displayOrder: 30, dataType: "multi_select" })
const HIDDEN = field({ fieldId: "f4", fieldKey: "hidden_field", label: "Hidden", displayOrder: 40, hidden: true })

function renderChips(props: Partial<React.ComponentProps<typeof FieldChips>> = {}) {
  const onToggle = vi.fn()
  const onSelectAll = vi.fn()
  const onClear = vi.fn()
  render(
    <TooltipProvider>
      <FieldChips
        schema={schemaWith([CLASSIFICATION, AUTHOR, TAGS, HIDDEN])}
        loading={false}
        selected={new Set(["classification_level"])}
        onToggle={onToggle}
        onSelectAll={onSelectAll}
        onClear={onClear}
        {...props}
      />
    </TooltipProvider>
  )
  return { onToggle, onSelectAll, onClear }
}

describe("FieldChips", () => {
  it("toggles a non-mandatory chip and reports it via onToggle", () => {
    const { onToggle } = renderChips()
    const authorChip = screen.getByRole("button", { name: /Author/ })
    expect(authorChip).toHaveAttribute("aria-pressed", "false")
    fireEvent.click(authorChip)
    expect(onToggle).toHaveBeenCalledWith("author")
  })

  it("does not call onToggle for a mandatory chip, and it stays pressed/disabled", () => {
    const { onToggle } = renderChips()
    const classificationChip = screen.getByRole("button", { name: /Classification Level/ })
    expect(classificationChip).toHaveAttribute("aria-pressed", "true")
    expect(classificationChip).toBeDisabled()
    fireEvent.click(classificationChip)
    expect(onToggle).not.toHaveBeenCalled()
  })

  it("renders a lock affordance for the mandatory field", () => {
    renderChips()
    const classificationChip = screen.getByRole("button", { name: /Classification Level/ })
    expect(classificationChip.querySelector("svg")).toBeTruthy()
  })

  it("never renders a hidden field as a chip", () => {
    renderChips()
    expect(screen.queryByText("Hidden")).not.toBeInTheDocument()
  })

  it("Select all calls onSelectAll", () => {
    const { onSelectAll } = renderChips()
    fireEvent.click(screen.getByText("Select all"))
    expect(onSelectAll).toHaveBeenCalledTimes(1)
  })

  it("Clear calls onClear", () => {
    const { onClear } = renderChips()
    fireEvent.click(screen.getByText("Clear"))
    expect(onClear).toHaveBeenCalledTimes(1)
  })
})

describe("mandatoryRequestedFields", () => {
  it("returns only mandatory, visible field keys", () => {
    const result = mandatoryRequestedFields(schemaWith([CLASSIFICATION, AUTHOR, TAGS, HIDDEN]))
    expect(result).toEqual(new Set(["classification_level"]))
  })
})

describe("defaultRequestedFields", () => {
  it("includes mandatory fields and ocrExtractable fields", () => {
    const ocrField = field({ fieldId: "f5", fieldKey: "reference_number", ocrExtractable: true })
    const result = defaultRequestedFields(schemaWith([CLASSIFICATION, AUTHOR, ocrField]))
    expect(result).toEqual(new Set(["classification_level", "reference_number"]))
  })

  it("excludes hidden fields even if mandatory", () => {
    const hiddenMandatory = field({ fieldId: "f6", fieldKey: "hidden_mandatory", mandatory: true, hidden: true })
    const result = defaultRequestedFields(schemaWith([CLASSIFICATION, hiddenMandatory]))
    expect(result).toEqual(new Set(["classification_level"]))
  })
})

describe("reconcileRequestedFields (reset on document-type change)", () => {
  it("resets to the new type's defaults, keeping still-valid prior selections", () => {
    // User was on a schema with classification_level (mandatory) + author + tags selected.
    const previous = new Set(["classification_level", "author", "tags"])
    // New document type's schema only has classification_level and author (no tags) --
    // plus a new mandatory field that wasn't there before.
    const newMandatory = field({ fieldId: "f7", fieldKey: "retention_class", mandatory: true, displayOrder: 5 })
    const newSchema = schemaWith([CLASSIFICATION, AUTHOR, newMandatory])

    const result = reconcileRequestedFields(previous, newSchema)

    // Keeps both mandatory fields (classification_level from before, retention_class new),
    // keeps "author" since it's still valid, but drops "tags" since it's not on the new schema.
    expect(result).toEqual(new Set(["classification_level", "retention_class", "author"]))
  })

  it("never drops a mandatory field even if it wasn't in the previous selection", () => {
    const result = reconcileRequestedFields(new Set(), schemaWith([CLASSIFICATION]))
    expect(result).toEqual(new Set(["classification_level"]))
  })
})

describe("ocrMatchNote (why a field was or wasn't found)", () => {
  it("not_found via the field-name fallback names the label it searched for", () => {
    expect(ocrMatchNote({ status: "not_found", confidence: null, matchBasis: "field_name" }, "PO Date")).toBe(
      "No label hints apply to this document type — searched for the field name “PO Date”"
    )
  })

  it("not_found despite configured label hints says so", () => {
    expect(ocrMatchNote({ status: "not_found", confidence: null, matchBasis: "label_hint" }, "PO Date")).toBe(
      "No value found under this field's label hints"
    )
  })

  it("a value found only by field name gets a small note, for both extracted and low_confidence", () => {
    expect(ocrMatchNote({ status: "extracted", confidence: 0.8, matchBasis: "field_name" }, "PO Date")).toBe("Matched by field name")
    expect(ocrMatchNote({ status: "low_confidence", confidence: 0.4, matchBasis: "field_name" }, "PO Date")).toBe("Matched by field name")
  })

  it("adds nothing for other bases, or for results recorded before matchBasis existed", () => {
    expect(ocrMatchNote({ status: "extracted", confidence: 0.9, matchBasis: "label_hint" }, "PO Date")).toBeNull()
    expect(ocrMatchNote({ status: "extracted", confidence: 0.95, matchBasis: "zone" }, "PO Date")).toBeNull()
    expect(ocrMatchNote({ status: "not_found", confidence: null, matchBasis: null }, "PO Date")).toBeNull()
    expect(ocrMatchNote({ status: "not_found", confidence: null }, "PO Date")).toBeNull()
  })
})
