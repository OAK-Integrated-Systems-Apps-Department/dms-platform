// @vitest-environment node
import { describe, expect, it } from "vitest"
import { FileText } from "lucide-react"

import { filterNavSections, findActiveHref, groupHasHref } from "./nav-utils"
import type { NavItem, NavSection } from "./types"

const item = (label: string, href?: string): NavItem => ({
  label,
  href,
  icon: FileText,
})

const sections: NavSection[] = [
  {
    items: [item("Dashboard", "/"), item("My Documents", "/documents")],
    groups: [
      {
        label: "OCR",
        icon: FileText,
        items: [item("Scan", "/documents/scan"), item("Review", "/documents/review")],
      },
    ],
  },
  {
    label: "Administration",
    items: [],
    groups: [
      { label: "Access", icon: FileText, items: [item("Users", "/admin/users")] },
      { label: "Security", icon: FileText, items: [item("Audit", "/admin/audit-log")] },
    ],
  },
]

describe("filterNavSections", () => {
  it("drops hidden items, then empty groups, then empty sections", () => {
    const out = filterNavSections(sections, (i) => !i.href?.startsWith("/admin"))
    expect(out).toHaveLength(1)
    expect(out[0].groups).toHaveLength(1)
  })

  it("keeps a group that still has one visible item", () => {
    const out = filterNavSections(sections, (i) => i.label === "Users")
    expect(out).toHaveLength(1)
    expect(out[0].label).toBe("Administration")
    expect(out[0].groups.map((g) => g.label)).toEqual(["Access"])
  })

  it("treats a section without groups as having none", () => {
    const out = filterNavSections([{ items: [item("Dashboard", "/")] }], () => true)
    expect(out[0].groups).toEqual([])
  })
})

describe("findActiveHref", () => {
  it("picks the longest match so only one item is active", () => {
    expect(findActiveHref(sections, "/documents/scan")).toBe("/documents/scan")
    expect(findActiveHref(sections, "/documents/abc123")).toBe("/documents")
  })

  it("matches / only exactly", () => {
    expect(findActiveHref(sections, "/")).toBe("/")
    expect(findActiveHref(sections, "/nowhere")).toBeUndefined()
  })

  it("matches on path segments, not string prefixes", () => {
    expect(findActiveHref(sections, "/documents-archive")).toBeUndefined()
  })
})

describe("groupHasHref", () => {
  it("is true only for the group holding the active href", () => {
    const [ocr] = sections[0].groups!
    expect(groupHasHref(ocr, "/documents/scan")).toBe(true)
    expect(groupHasHref(ocr, "/documents")).toBe(false)
    expect(groupHasHref(ocr, undefined)).toBe(false)
  })
})
