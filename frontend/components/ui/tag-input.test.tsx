import "@testing-library/jest-dom/vitest"
import { useState } from "react"
import { fireEvent, render, screen } from "@testing-library/react"
import { describe, expect, it } from "vitest"

import { labelHintError, normalizeLabelHint } from "@/lib/metadata-fields/label-hints"

import { TagInput, addTags, extractCompletedTags, removeTagAt, splitTagText } from "./tag-input"

describe("splitTagText", () => {
  it("splits on commas and line breaks, trimming each piece and dropping empties", () => {
    expect(splitTagText("PO Number, Order No,,  Number ")).toEqual(["PO Number", "Order No", "Number"])
    expect(splitTagText("Invoice No\r\nInv #\n\nInvoice#")).toEqual(["Invoice No", "Inv #", "Invoice#"])
  })

  it("returns a single piece when there's no separator, and nothing for blank text", () => {
    expect(splitTagText("  Vendor  ")).toEqual(["Vendor"])
    expect(splitTagText("  ")).toEqual([])
    expect(splitTagText(",\n,")).toEqual([])
  })
})

describe("extractCompletedTags", () => {
  it("returns everything as still-being-typed when there's no separator", () => {
    expect(extractCompletedTags("PO Num")).toEqual({ completed: [], remainder: "PO Num" })
  })

  it("completes everything before the last separator and keeps the rest as the remainder", () => {
    expect(extractCompletedTags("PO Number, Order No, Num")).toEqual({ completed: ["PO Number", "Order No"], remainder: "Num" })
  })

  it("completes the whole text when it ends with a separator", () => {
    expect(extractCompletedTags("PO Number,")).toEqual({ completed: ["PO Number"], remainder: "" })
    expect(extractCompletedTags("Vendor\n")).toEqual({ completed: ["Vendor"], remainder: "" })
  })
})

describe("addTags", () => {
  it("appends new tags in order and returns a new array", () => {
    const current = ["PO Number"]
    const result = addTags(current, ["Order No", "Number"])
    expect(result.tags).toEqual(["PO Number", "Order No", "Number"])
    expect(result.tags).not.toBe(current)
    expect(result).toMatchObject({ rejected: [], error: null })
  })

  it("silently skips case-insensitive duplicates of existing or earlier incoming tags, keeping the original's place", () => {
    const result = addTags(["PO Number"], ["po number", "Order No", "ORDER NO"])
    expect(result.tags).toEqual(["PO Number", "Order No"])
    expect(result).toMatchObject({ rejected: [], error: null })
  })

  it("returns the SAME array when nothing was added, so callers can skip a no-op onChange", () => {
    const current = ["PO Number"]
    expect(addTags(current, ["PO NUMBER", "  "]).tags).toBe(current)
    expect(addTags(current, []).tags).toBe(current)
  })

  it("applies `normalize` before duplicate checks", () => {
    const result = addTags(["PO Number"], ["  po   number ", "Order \t No"], { normalize: normalizeLabelHint })
    expect(result.tags).toEqual(["PO Number", "Order No"])
  })

  it("refuses invalid tags, reporting the first reason and handing the refused text back", () => {
    const result = addTags(["PO Number"], ["x", "Order No", "##"], { normalize: normalizeLabelHint, validate: labelHintError })
    expect(result.tags).toEqual(["PO Number", "Order No"])
    expect(result.rejected).toEqual(["x", "##"])
    expect(result.error).toMatch(/at least 2/)
  })

  it("refuses tags past maxTags instead of silently dropping them", () => {
    const result = addTags(["A1", "B2"], ["C3", "D4", "E5"], { maxTags: 3 })
    expect(result.tags).toEqual(["A1", "B2", "C3"])
    expect(result.rejected).toEqual(["D4", "E5"])
    expect(result.error).toMatch(/at most 3/)
  })

  it("doesn't count a duplicate against maxTags", () => {
    const result = addTags(["A1", "B2"], ["a1", "C3"], { maxTags: 3 })
    expect(result.tags).toEqual(["A1", "B2", "C3"])
    expect(result.error).toBeNull()
  })
})

describe("removeTagAt", () => {
  it("removes the tag at the given index, preserving the others' order", () => {
    expect(removeTagAt(["A1", "B2", "C3"], 1)).toEqual(["A1", "C3"])
    expect(removeTagAt(["A1", "B2", "C3"], 2)).toEqual(["A1", "B2"])
  })

  it("returns the same array for an out-of-range index", () => {
    const current = ["A1"]
    expect(removeTagAt(current, -1)).toBe(current)
    expect(removeTagAt(current, 1)).toBe(current)
  })
})

// Controlled harness: TagInput keeps only the half-typed text itself.
function Harness({ initial = [] as string[] }) {
  const [value, setValue] = useState(initial)
  return (
    <>
      <TagInput id="hints" value={value} onChange={setValue} />
      <output data-testid="value">{JSON.stringify(value)}</output>
    </>
  )
}

const input = () => screen.getByRole("textbox")
const committed = () => JSON.parse(screen.getByTestId("value").textContent ?? "[]")

describe("TagInput with an IME", () => {
  it("leaves the Enter that confirms a conversion to the IME instead of committing a chip", () => {
    render(<Harness />)
    fireEvent.compositionStart(input())
    fireEvent.change(input(), { target: { value: "ちゅうもん" } })
    // Chrome/Firefox: the confirming keydown is still flagged as composing.
    fireEvent.keyDown(input(), { key: "Enter", isComposing: true })
    fireEvent.compositionEnd(input())
    expect(committed()).toEqual([])
    expect(input()).toHaveValue("ちゅうもん")

    // Safari: it arrives after compositionend, recognisable only by keyCode 229.
    fireEvent.keyDown(input(), { key: "Enter", keyCode: 229 })
    expect(committed()).toEqual([])
    expect(input()).toHaveValue("ちゅうもん")

    // A real Enter afterwards does commit it.
    fireEvent.keyDown(input(), { key: "Enter" })
    expect(committed()).toEqual(["ちゅうもん"])
    expect(input()).toHaveValue("")
  })

  it("doesn't let Backspace mid-composition remove the last chip", () => {
    render(<Harness initial={["PO Number"]} />)
    fireEvent.keyDown(input(), { key: "Backspace", isComposing: true })
    expect(committed()).toEqual(["PO Number"])
  })

  it("doesn't split on a separator inside text that's still being composed", () => {
    render(<Harness />)
    const composing = new InputEvent("input", { bubbles: true, isComposing: true })
    // React's onChange reads the value off the element, so set it the way a browser would before the event.
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")?.set?.call(input(), "注文,番号")
    fireEvent(input(), composing)
    expect(committed()).toEqual([])
    expect(input()).toHaveValue("注文,番号")
  })
})
