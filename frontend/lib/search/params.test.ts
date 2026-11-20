import { describe, expect, it } from "vitest"

import {
  activeFilterCount,
  apiParams,
  EMPTY_FILTERS,
  filtersFromParams,
  opsFor,
  paramsFromFilters,
  searchSignature,
} from "./params"
import type { SearchFilters } from "./types"

const parse = (qs: string) => filtersFromParams(new URLSearchParams(qs))
const filters = (patch: Partial<SearchFilters>): SearchFilters => ({ ...EMPTY_FILTERS, ...patch })

describe("search URL <-> filters", () => {
  it("an empty URL is the defaults", () => {
    expect(parse("")).toEqual(EMPTY_FILTERS)
    expect(paramsFromFilters(EMPTY_FILTERS).toString()).toBe("")
  })

  it("round-trips every criterion", () => {
    const f = filters({
      q: "annual budget",
      type: "t1",
      department: "d1",
      owner: "u1",
      createdFrom: "2026-01-01",
      createdTo: "2026-02-01",
      modifiedFrom: "2026-03-01",
      modifiedTo: "2026-04-01",
      statuses: ["inactive", "archived"],
      folderId: "f1",
      includeSubfolders: true,
      meta: [
        { fieldId: "a", op: "gte", value: "1000" },
        { fieldId: "b", op: "contains", value: "10:30 sharp" },
      ],
      sort: "title",
      dir: "asc",
    })
    expect(parse(paramsFromFilters(f).toString())).toEqual(f)
  })

  it("leaves defaults out so addresses stay short", () => {
    const qs = paramsFromFilters(filters({ q: "zebra" })).toString()
    expect(qs).toBe("q=zebra")
    expect(paramsFromFilters(filters({ statuses: ["active", "inactive", "archived"] })).get("status")).toBe("all")
  })

  it("a stale or hand-edited link still works: unknown values fall back, bad filters are dropped", () => {
    const f = parse("status=pending&sort=random&dir=sideways&meta=nonsense&meta=a:between:1&meta=a:eq:ok&includeSubfolders=true")
    expect(f.statuses).toEqual(["active"])
    expect(f.sort).toBe("relevance")
    expect(f.dir).toBe("desc")
    expect(f.meta).toEqual([{ fieldId: "a", op: "eq", value: "ok" }])
    expect(f.includeSubfolders).toBe(true)
  })

  it("'status=all' and comma lists both work, without duplicates", () => {
    expect(parse("status=all").statuses).toEqual(["active", "inactive", "archived"])
    expect(parse("status=archived,archived,inactive").statuses).toEqual(["archived", "inactive"])
  })

  it("'include subfolders' means nothing without a folder", () => {
    expect(paramsFromFilters(filters({ includeSubfolders: true })).has("includeSubfolders")).toBe(false)
  })

  it("blank metadata rows are not sent", () => {
    const p = paramsFromFilters(filters({ meta: [{ fieldId: "", op: "eq", value: "x" }, { fieldId: "a", op: "eq", value: "  " }] }))
    expect(p.getAll("meta")).toEqual([])
  })

  it("the API request always carries sort, direction and page size, plus the cursor when there is one", () => {
    const first = apiParams(filters({ q: "x" }), null)
    expect(first.get("sort")).toBe("relevance")
    expect(first.get("dir")).toBe("desc")
    expect(first.get("size")).toBe("20")
    expect(first.has("cursor")).toBe(false)
    expect(apiParams(filters({ q: "x" }), "abc.def").get("cursor")).toBe("abc.def")
  })

  it("counts the criteria set besides the keywords", () => {
    expect(activeFilterCount(EMPTY_FILTERS)).toBe(0)
    expect(activeFilterCount(filters({ q: "only words" }))).toBe(0)
    expect(activeFilterCount(filters({ type: "t", owner: "u", statuses: ["archived"], meta: [{ fieldId: "a", op: "eq", value: "1" }] }))).toBe(4)
  })

  it("the signature changes with any criterion, so paging restarts, and ignores nothing that matters", () => {
    const base = searchSignature(filters({ q: "x" }))
    expect(searchSignature(filters({ q: "x" }))).toBe(base)
    expect(searchSignature(filters({ q: "y" }))).not.toBe(base)
    expect(searchSignature(filters({ q: "x", type: "t" }))).not.toBe(base)
    expect(searchSignature(filters({ q: "x", sort: "title" }))).not.toBe(base)
    expect(searchSignature(filters({ q: "x", dir: "asc" }))).not.toBe(base)
  })

  it("offers only sensible comparisons for each metadata type", () => {
    expect(opsFor("number")).toEqual(["eq", "gte", "lte"])
    expect(opsFor("date")).toEqual(["eq", "gte", "lte"])
    expect(opsFor("dropdown")).toEqual(["eq"])
    expect(opsFor("boolean")).toEqual(["eq"])
    expect(opsFor("text")).toContain("contains")
  })
})
