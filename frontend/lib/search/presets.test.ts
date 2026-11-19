import { describe, expect, it } from "vitest"

import { EMPTY_FILTERS } from "./params"
import { criteriaFromFilters, filtersFromCriteria, hasPresetCriteria, sameCriteria } from "./presets"
import type { SearchFilters } from "./types"

const f = (patch: Partial<SearchFilters>): SearchFilters => ({ ...EMPTY_FILTERS, ...patch })

describe("preset criteria", () => {
  it("only sends what is set, and never a page size or cursor", () => {
    const c = criteriaFromFilters(f({ q: " renewals ", type: "t1" }))
    expect(c).toEqual({ schemaVersion: 1, q: "renewals", documentTypeId: "t1" })
    expect(Object.keys(c)).not.toContain("size")
    expect(Object.keys(c)).not.toContain("cursor")
  })

  it("round-trips every filter through criteria and back", () => {
    const filters = f({
      q: "invoice 2024",
      type: "t1",
      department: "d1",
      owner: "u1",
      createdFrom: "2026-01-01T00:00:00Z",
      createdTo: "2026-02-01T00:00:00Z",
      modifiedFrom: "2026-03-01T00:00:00Z",
      modifiedTo: "2026-04-01T00:00:00Z",
      statuses: ["active", "archived"],
      folderId: "f1",
      includeSubfolders: true,
      meta: [{ fieldId: "m1", op: "gte", value: "100" }],
      sort: "modified",
      dir: "asc",
    })
    expect(filtersFromCriteria(criteriaFromFilters(filters))).toEqual(filters)
  })

  it("running a preset starts from the defaults for everything it does not name", () => {
    expect(filtersFromCriteria({ q: "x" })).toEqual(f({ q: "x" }))
  })

  it("drops 'include subfolders' without a folder", () => {
    expect(filtersFromCriteria({ includeSubfolders: true }).includeSubfolders).toBe(false)
  })

  describe("hasPresetCriteria (the server's rule: at least one real criterion)", () => {
    it("is false for nothing, for a sort alone, and for the default active state", () => {
      expect(hasPresetCriteria(f({}))).toBe(false)
      expect(hasPresetCriteria(f({ sort: "title", dir: "asc" }))).toBe(false)
      expect(hasPresetCriteria(f({ statuses: ["active"] }))).toBe(false)
      expect(hasPresetCriteria(f({ q: "   " }))).toBe(false)
    })

    it("is false for 'include subfolders' without a folder", () => {
      expect(hasPresetCriteria(f({ includeSubfolders: true }))).toBe(false)
    })

    it("is true for a keyword, a filter, another lifecycle state, or a complete metadata filter", () => {
      expect(hasPresetCriteria(f({ q: "contract" }))).toBe(true)
      expect(hasPresetCriteria(f({ department: "d1" }))).toBe(true)
      expect(hasPresetCriteria(f({ statuses: ["archived"] }))).toBe(true)
      expect(hasPresetCriteria(f({ meta: [{ fieldId: "m1", op: "eq", value: "x" }] }))).toBe(true)
      expect(hasPresetCriteria(f({ meta: [{ fieldId: "m1", op: "eq", value: " " }] }))).toBe(false)
    })
  })
})

describe("comparing criteria", () => {
  it("ignores key order, paging, the schema version and every default", () => {
    const fromPage = { ...criteriaFromFilters(f({ q: "contract", department: "d1" })) }
    const fromServer = { departmentId: "d1", statuses: ["active"], q: "contract", schemaVersion: 1, size: 20, cursor: "abc", ascending: false, sort: "relevance" }
    expect(sameCriteria(fromPage, fromServer)).toBe(true)
    expect(sameCriteria({ b: 1, q: "x" }, { q: "x", b: 1 })).toBe(true)
  })

  it("tells different searches apart, including metadata and status order", () => {
    expect(sameCriteria({ q: "a" }, { q: "b" })).toBe(false)
    expect(sameCriteria({ q: "a", statuses: ["archived", "inactive"] }, { q: "a", statuses: ["inactive", "archived"] })).toBe(true)
    expect(sameCriteria({ q: "a", statuses: ["archived"] }, { q: "a" })).toBe(false)
    const m1 = { fieldId: "f1", op: "eq", value: "x" }
    const m2 = { fieldId: "f2", op: "gte", value: "5" }
    expect(sameCriteria({ meta: [m1, m2] }, { meta: [m2, m1] })).toBe(true)
    expect(sameCriteria({ meta: [m1] }, { meta: [{ ...m1, value: "y" }] })).toBe(false)
    expect(sameCriteria({ q: "a", includeSubfolders: true }, { q: "a" })).toBe(true)
    expect(sameCriteria({ folderId: "x", includeSubfolders: true }, { folderId: "x" })).toBe(false)
  })
})
