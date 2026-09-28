import { describe, expect, it } from "vitest"

import {
  MAX_LABEL_HINTS,
  labelHintCaseFoldKey,
  labelHintError,
  labelHintsError,
  normalizeLabelHint,
  normalizeLabelHints,
} from "./label-hints"

// Parity with dms-core's LabelAliasNormalizer (normalizeOne / caseFoldKey /
// problemWith) for the rules added after the first mirror: invisible format
// characters stripped (except ZWNJ/ZWJ), control characters rejected, and
// duplicates detected per code point like Postgres's lower().
describe("server parity: invisible, control and case-fold rules", () => {
  it("strips invisible format characters (zero-width space, soft hyphen, BOM) before collapsing", () => {
    expect(normalizeLabelHint("PO\u200B No")).toBe("PO No")
    expect(normalizeLabelHint("\uFEFFPO\u00ADNo")).toBe("PONo")
    expect(normalizeLabelHint("PO \u200B No")).toBe("PO No")
  })

  it("keeps ZWNJ/ZWJ, which are part of correct spelling in Sinhala/Persian/Indic scripts", () => {
    expect(normalizeLabelHint("ශ්\u200Dරී")).toBe("ශ්\u200Dරී")
    expect(normalizeLabelHint("می\u200Cخواهم")).toBe("می\u200Cخواهم")
  })

  it("rejects control characters instead of stripping them", () => {
    expect(labelHintError(normalizeLabelHint("PO\u0000No"))).toMatch(/control characters/)
    expect(labelHintError(normalizeLabelHint("PO\u001BNo"))).toMatch(/control characters/)
  })

  it("treats a zero-width-padded copy as a duplicate of the visible hint", () => {
    expect(normalizeLabelHints(["No", "No\u200B"])).toEqual(["No"])
  })

  it("case-folds one code point at a time, like the unique index's lower()", () => {
    expect(labelHintCaseFoldKey("\u0130D")).toBe("id")
    expect(labelHintCaseFoldKey("ΟΔΟΣ")).toBe(labelHintCaseFoldKey("οδοσ"))
    expect(normalizeLabelHints(["\u0130D", "id"])).toEqual(["\u0130D"])
    expect(normalizeLabelHints(["ΟΔΟΣ", "οδοσ"])).toEqual(["ΟΔΟΣ"])
  })
})

describe("normalizeLabelHint", () => {
  it("trims and collapses internal whitespace runs (spaces, tabs, newlines) to one space", () => {
    expect(normalizeLabelHint("  PO \t  Number\n ")).toBe("PO Number")
  })

  it("leaves an already-normal hint unchanged", () => {
    expect(normalizeLabelHint("Invoice No")).toBe("Invoice No")
  })

  // Pinned to dms-core's LabelAliasNormalizer (Java \s under
  // UNICODE_CHARACTER_CLASS, then String#strip), not to JS's \s/trim().
  it("collapses exactly the Unicode White_Space characters the server does", () => {
    expect(normalizeLabelHint("PO 　Number")).toBe("PO Number") // no-break + ideographic space
    expect(normalizeLabelHint("PO\u0085Number")).toBe("PO Number") // NEL: White_Space, but not JS \s
    expect(normalizeLabelHint("PO\uFEFFNumber")).toBe("PONumber") // BOM: not White_Space, but Cf -- stripped as invisible, like the server
  })

  it("strips the same edge characters as Java's String#strip", () => {
    expect(normalizeLabelHint("\u001cPO\u001f")).toBe("PO")
    expect(normalizeLabelHint("\uFEFFPO")).toBe("PO") // BOM is Cf: stripped before the edge strip ever sees it
  })
})

describe("normalizeLabelHints", () => {
  it("normalizes every entry and keeps the submitted (priority) order", () => {
    expect(normalizeLabelHints([" Purchase  Order Number", "PO Number ", "Number"])).toEqual([
      "Purchase Order Number",
      "PO Number",
      "Number",
    ])
  })

  it("drops case-insensitive duplicates, keeping the FIRST occurrence and its casing", () => {
    expect(normalizeLabelHints(["PO Number", "Order No", "po number", "PO  NUMBER"])).toEqual(["PO Number", "Order No"])
  })

  it("treats entries that only differ by whitespace as duplicates", () => {
    expect(normalizeLabelHints(["Ref No", "  ref   no  "])).toEqual(["Ref No"])
  })

  it("drops blank and whitespace-only entries", () => {
    expect(normalizeLabelHints(["", "   ", "\t", "Vendor"])).toEqual(["Vendor"])
  })

  it("does not drop invalid-but-non-blank entries -- that's labelHintsError's job", () => {
    expect(normalizeLabelHints(["#", "x"])).toEqual(["#", "x"])
  })
})

describe("labelHintError", () => {
  it("accepts a 2-character hint and a 100-character hint", () => {
    expect(labelHintError("PO")).toBeNull()
    expect(labelHintError("a".repeat(100))).toBeNull()
  })

  it("rejects a hint shorter than 2 or longer than 100 characters", () => {
    expect(labelHintError("P")).toMatch(/at least 2/)
    expect(labelHintError("a".repeat(101))).toMatch(/100 characters or fewer/)
  })

  it("rejects a hint with no letter or digit", () => {
    expect(labelHintError("#:")).toMatch(/letter or digit/)
    expect(labelHintError("--")).toMatch(/letter or digit/)
  })

  it("accepts punctuation as long as there's a letter or digit, including non-Latin letters", () => {
    expect(labelHintError("ref#")).toBeNull()
    expect(labelHintError("#1")).toBeNull()
    expect(labelHintError("Número")).toBeNull()
    expect(labelHintError("請求書")).toBeNull()
    expect(labelHintError("٣٤")).toBeNull() // Arabic-Indic decimal digits (Nd)
  })

  // Java's Character.isLetterOrDigit (letters + Nd only) is what dms-core
  // checks; \p{N} would also let these through and earn a 400.
  it("rejects hints whose only 'digits' aren't decimal digits, as the server does", () => {
    expect(labelHintError("①②")).toMatch(/letter or digit/) // No
    expect(labelHintError("½½")).toMatch(/letter or digit/) // No
    expect(labelHintError("²³")).toMatch(/letter or digit/) // No
    expect(labelHintError("ⅫⅫ")).toMatch(/letter or digit/) // Nl
  })

  it("counts length in code points, not UTF-16 units, as the server does", () => {
    // One astral character is 2 UTF-16 units but 1 code point: too short.
    expect(labelHintError("𠮷")).toMatch(/at least 2/)
    expect(labelHintError("𝐀")).toMatch(/at least 2/)
    expect(labelHintError("𠮷𠮷")).toBeNull()
    // 100 astral letters are 200 UTF-16 units but within the 100 limit.
    expect(labelHintError("𝐀".repeat(100))).toBeNull()
    expect(labelHintError("𝐀".repeat(101))).toMatch(/100 characters or fewer/)
  })
})

describe("labelHintsError", () => {
  it("accepts an empty list (clears all hints)", () => {
    expect(labelHintsError([])).toBeNull()
  })

  it("reports the first invalid hint by name", () => {
    expect(labelHintsError(["PO Number", "#", "x"])).toMatch(/^"#": /)
  })

  it("checks length after whitespace normalization", () => {
    // 1 visible character padded with spaces is still too short once trimmed.
    expect(labelHintsError(["   P   "])).toMatch(/at least 2/)
    // 100 characters once internal runs collapse, even though the raw text is longer.
    expect(labelHintsError([`${"a".repeat(50)}     ${"b".repeat(49)}`])).toBeNull()
  })

  it(`allows exactly ${MAX_LABEL_HINTS} hints but not ${MAX_LABEL_HINTS + 1}`, () => {
    const hints = Array.from({ length: MAX_LABEL_HINTS + 1 }, (_, i) => `Hint ${i}`)
    expect(labelHintsError(hints.slice(0, MAX_LABEL_HINTS))).toBeNull()
    expect(labelHintsError(hints)).toMatch(/at most 20/)
  })

  it("counts toward the limit only after de-duplication", () => {
    const hints = Array.from({ length: MAX_LABEL_HINTS }, (_, i) => `Hint ${i}`)
    expect(labelHintsError([...hints, "HINT 0", "hint 1"])).toBeNull()
  })
})
