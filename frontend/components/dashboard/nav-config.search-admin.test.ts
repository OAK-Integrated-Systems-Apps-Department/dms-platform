// @vitest-environment node
import { describe, expect, it } from "vitest"

import { navSections } from "./nav-config"

describe("Search Index admin nav item", () => {
  const administration = navSections.find((section) => section.label === "Administration")
  const all = (administration?.groups ?? []).flatMap((g) => g.items)
  const item = all.find((i) => i.label === "Search Index")

  it("sits in Administration and goes to /admin/search", () => {
    expect(item?.href).toBe("/admin/search")
  })

  it("is for administrators only (the endpoints are tenant admin only), with no permission code of its own", () => {
    expect(item?.adminOnly).toBe(true)
    expect(item?.permission).toBeUndefined()
  })
})
