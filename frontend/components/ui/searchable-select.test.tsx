import { fireEvent, render, screen } from "@testing-library/react"
import { describe, expect, it, vi } from "vitest"

import { SearchableSelect } from "./searchable-select"

const OPTIONS = [
  { value: "a", label: "Finance" },
  { value: "b", label: "Finance / Invoices" },
  { value: "c", label: "Legal" },
]

/** The list's outer box: search input -> its wrapper -> the panel. */
function panel() {
  return screen.getByPlaceholderText("Search folders…").parentElement!.parentElement!
}

function open(inline: boolean, onChange = vi.fn()) {
  render(
    <SearchableSelect
      options={OPTIONS}
      selected=""
      onChange={onChange}
      allLabel="No folder (top level)"
      searchPlaceholder="Search folders…"
      inline={inline}
    />
  )
  fireEvent.click(screen.getByRole("button", { name: /no folder/i }))
  return onChange
}

describe("SearchableSelect", () => {
  it("normally floats over the page at a fixed width (filter bars)", () => {
    open(false)
    expect(panel().className).toContain("absolute")
    expect(panel().className).toContain("w-[240px]")
  })

  it("inline: opens in page flow at full width, so a dialog grows instead of cropping the list", () => {
    open(true)
    expect(panel().className).not.toContain("absolute")
    expect(panel().className).not.toContain("w-[240px]")
    expect(panel().className).toContain("w-full")
  })

  it("searching narrows the list and a result can be picked, inline or not", () => {
    for (const inline of [false, true]) {
      const onChange = open(inline)
      fireEvent.change(screen.getByPlaceholderText("Search folders…"), { target: { value: "fin" } })

      expect(screen.getByRole("button", { name: "Finance" })).toBeInTheDocument()
      expect(screen.getByRole("button", { name: "Finance / Invoices" })).toBeInTheDocument()
      expect(screen.queryByRole("button", { name: "Legal" })).not.toBeInTheDocument()

      fireEvent.click(screen.getByRole("button", { name: "Finance / Invoices" }))
      expect(onChange).toHaveBeenCalledWith("b")
      document.body.innerHTML = ""
    }
  })
})
