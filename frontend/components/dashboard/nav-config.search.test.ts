// @vitest-environment node
import { describe, expect, it } from "vitest"

import { PermissionCodes } from "@/lib/auth/permission-codes"

import { navSections } from "./nav-config"
import { filterNavSections, findActiveHref } from "./nav-utils"

describe("Search nav item (Document Search & Retrieval)", () => {
  const documents = navSections.find((section) => section.label === "Documents")
  const item = documents?.items.find((i) => i.label === "Search")

  it("sits in Documents and goes to /search", () => {
    expect(item).toBeDefined()
    expect(item?.href).toBe("/search")
  })

  it("is shown only to people holding the search permission", () => {
    expect(item?.permission).toBe(PermissionCodes.DSR_SEARCH_DOCUMENTS)
    const visibleTo = (granted: string[]) =>
      filterNavSections(navSections, (i) => {
        const needs = i.permission
        return !needs || [needs].flat().some((code) => granted.includes(code))
      }).flatMap((s) => s.items.map((i) => i.label))
    expect(visibleTo([PermissionCodes.DSR_SEARCH_DOCUMENTS])).toContain("Search")
    expect(visibleTo([PermissionCodes.DOCM_DOCUMENTS_VIEW])).not.toContain("Search")
    expect(visibleTo([])).not.toContain("Search")
  })

  it("highlights only Search while on /search, and not My Documents", () => {
    expect(findActiveHref(navSections, "/search")).toBe("/search")
    expect(findActiveHref(navSections, "/documents")).toBe("/documents")
  })

  it("the permission code is the same string the backend checks", () => {
    expect(PermissionCodes.DSR_SEARCH_DOCUMENTS).toBe("dsr.search-documents")
  })
})
