// @vitest-environment node
import { describe, expect, it } from "vitest"

import { PermissionCodes } from "@/lib/auth/permission-codes"

import { navSections } from "./nav-config"
import type { NavItem } from "./types"

function everyItem(): NavItem[] {
  return navSections.flatMap((section) => [
    ...(section.items ?? []),
    ...(section.groups ?? []).flatMap((group) => group.items),
  ])
}

describe("Reports nav items", () => {
  const items = everyItem().filter((i) => i.label === "Reports")
  const user = items.find((i) => i.href === "/reports")
  const admin = items.find((i) => i.href === "/admin/reports")

  it("offers reports to anyone who may run or build them, and only while the organisation has them switched on", () => {
    expect(user?.permission).toEqual([PermissionCodes.CRG_RUN, PermissionCodes.CRG_BUILD])
    expect(user?.feature).toBe("reports")
  })

  it("puts the administration screen behind the configure permission, with no feature gate so reports can be switched back on", () => {
    expect(admin?.permission).toEqual([PermissionCodes.CRG_CONFIGURE_REPORTS])
    expect(admin?.feature).toBeUndefined()
  })
})
