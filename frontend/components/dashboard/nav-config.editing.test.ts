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

describe("Online Editing nav item", () => {
  const item = everyItem().find((i) => i.label === "Online Editing")

  it("points at the organisation's own Online Editing page, for those who may change or see its settings", () => {
    expect(item?.href).toBe("/admin/editing")
    expect(item?.permission).toEqual([
      PermissionCodes.CICO_EDITING_SELECT_PROVIDER,
      PermissionCodes.CICO_EDITING_CONFIGURE_CONNECTION,
    ])
  })

  it("is shown only once the platform administrator has allowed online editing - nothing to switch before that", () => {
    expect(item?.feature).toBe("onlineEditing")
  })
})
