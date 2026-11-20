import { useState } from "react"
import { fireEvent, render, screen, within } from "@testing-library/react"
import { describe, expect, it, vi } from "vitest"

import { validateDefinition, type ReportDefinition } from "@/lib/reports/definition"

vi.mock("@/lib/reports/use-reports", () => ({
  useFieldValues: () => ({ data: { content: [{ value: "published", label: "Published" }], hasMore: false }, isLoading: false, isError: false, refetch: vi.fn() }),
}))

import { catalogFixture, definitionFixture } from "./fixtures"
import { ColumnsStep } from "./columns-step"
import { FiltersStep } from "./filters-step"

const catalog = catalogFixture()

function Filters({ initial = definitionFixture(), editableFields }: { initial?: ReportDefinition; editableFields?: string[] }) {
  const [def, setDef] = useState(initial)
  return (
    <>
      <FiltersStep definition={def} catalog={catalog} problems={validateDefinition(def, catalog)} onChange={setDef} onLabel={vi.fn()} editableFields={editableFields} />
      <output data-testid="json">{JSON.stringify(def)}</output>
    </>
  )
}

function Columns({ initial = definitionFixture() }: { initial?: ReportDefinition }) {
  const [def, setDef] = useState(initial)
  return (
    <>
      <ColumnsStep definition={def} catalog={catalog} problems={{}} onChange={setDef} />
      <output data-testid="json">{JSON.stringify(def)}</output>
    </>
  )
}

const current = () => JSON.parse(screen.getByTestId("json").textContent ?? "{}") as ReportDefinition

describe("FiltersStep", () => {
  it("adds a filter from the grouped, searchable field picker and removes it again", () => {
    render(<Filters />)
    expect(screen.getByText(/covers every document you can see/i)).toBeInTheDocument()

    fireEvent.click(screen.getByRole("button", { name: /add a filter/i }))
    fireEvent.change(screen.getByLabelText("Search fields"), { target: { value: "contract" } })
    expect(screen.getByText("Custom fields")).toBeInTheDocument()
    expect(screen.queryByText("Document details")).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole("button", { name: "Contract value" }))

    expect(current().filters).toEqual([{ field: "contractValue", operator: "gt", values: [""] }])
    expect(screen.getAllByTestId("filter-row")).toHaveLength(1)

    fireEvent.click(screen.getByRole("button", { name: "Remove the Contract value filter" }))
    expect(current().filters).toEqual([])
  })

  it("offers only the field's own operators, in plain words, and resets the values when one changes", () => {
    render(<Filters initial={definitionFixture({ filters: [{ field: "title", operator: "contains", values: ["alpha"] }] })} />)
    const select = screen.getByLabelText("Title comparison") as HTMLSelectElement
    expect(within(select).getAllByRole("option").map((o) => o.textContent)).toEqual(["contains", "is exactly", "is empty"])
    fireEvent.change(select, { target: { value: "is_empty" } })
    expect(current().filters[0]).toEqual({ field: "title", operator: "is_empty", values: [] })
    expect(screen.queryByLabelText("Title value")).not.toBeInTheDocument()
  })

  it("shows a problem for a filter without a value", () => {
    render(<Filters initial={definitionFixture({ filters: [{ field: "title", operator: "contains", values: [""] }] })} />)
    expect(screen.getByRole("alert")).toHaveTextContent("Give a value.")
  })

  it("takes relative date presets and two values for between", () => {
    render(<Filters initial={definitionFixture({ filters: [{ field: "createdAt", operator: "between", values: ["", ""] }] })} />)
    expect(screen.getByLabelText("Created from")).toBeInTheDocument()
    expect(screen.getByLabelText("Created to")).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText("Created comparison"), { target: { value: "relative" } })
    const preset = screen.getByLabelText("Created value") as HTMLSelectElement
    expect(within(preset).getByRole("option", { name: "in the last 30 days" })).toBeInTheDocument()
  })

  it("picks from the real choices of a choice field", () => {
    render(<Filters initial={definitionFixture({ filters: [{ field: "status", operator: "is_any_of", values: [] }] })} />)
    fireEvent.click(screen.getByRole("option", { name: "Published" }))
    expect(current().filters[0].values).toEqual(["published"])
  })

  it("lets only the open filters of a locked report be changed", () => {
    const initial = definitionFixture({
      filters: [
        { field: "title", operator: "contains", values: ["a"] },
        { field: "pageCount", operator: "gt", values: ["1"] },
      ],
    })
    render(<Filters initial={initial} editableFields={["title"]} />)
    expect(screen.getByLabelText("Title value")).toBeEnabled()
    expect(screen.getByLabelText("Page count value")).toBeDisabled()
    expect(screen.queryByRole("button", { name: /add a filter/i })).not.toBeInTheDocument()
  })
})

describe("ColumnsStep", () => {
  it("moves a column with the arrow buttons", () => {
    render(<Columns />)
    fireEvent.click(screen.getByRole("button", { name: "Move Status up" }))
    expect(current().columns).toEqual(["status", "title"])
    fireEvent.click(screen.getByRole("button", { name: "Move Status down" }))
    expect(current().columns).toEqual(["title", "status"])
  })

  it("cannot move the first column up or the last one down", () => {
    render(<Columns />)
    expect(screen.getByRole("button", { name: "Move Title up" })).toBeDisabled()
    expect(screen.getByRole("button", { name: "Move Status down" })).toBeDisabled()
  })

  it("reorders by drag and drop", () => {
    render(<Columns />)
    const items = within(screen.getByRole("list", { name: "Columns in order" })).getAllByRole("listitem")
    fireEvent.dragStart(items[1])
    fireEvent.drop(items[0])
    expect(current().columns).toEqual(["status", "title"])
  })

  it("adds and removes columns from the checklist and sets sort and grouping", () => {
    render(<Columns />)
    fireEvent.click(screen.getByLabelText("Page count"))
    expect(current().columns).toEqual(["title", "status", "pageCount"])
    fireEvent.click(screen.getByRole("button", { name: "Remove Title" }))
    expect(current().columns).toEqual(["status", "pageCount"])

    fireEvent.click(screen.getByRole("button", { name: /add a sort/i }))
    expect(current().sort).toEqual([{ field: "title", direction: "asc" }])
    fireEvent.change(screen.getByLabelText("Sort direction 1"), { target: { value: "desc" } })
    expect(current().sort[0].direction).toBe("desc")

    fireEvent.change(screen.getByLabelText("Group by"), { target: { value: "status" } })
    expect(current().groupBy).toBe("status")
  })
})
