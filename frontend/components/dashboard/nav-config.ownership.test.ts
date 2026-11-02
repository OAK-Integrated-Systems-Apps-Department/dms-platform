// @vitest-environment node
import { describe, expect, it } from "vitest"

import { PermissionCodes } from "@/lib/auth/permission-codes"

import { navSections } from "./nav-config"

describe("Document Ownership nav item", () => {
  const admin = navSections.find((section) => section.label === "Administration")
  const access = admin?.groups?.find((group) => group.label === "Access")
  const item = access?.items.find((i) => i.label === "Document Ownership")

  it("sits in Administration -> Access", () => {
    expect(item).toBeDefined()
    expect(item?.href).toBe("/admin/ownership")
  })

  it("shows for anyone holding one of the DOT screens' permissions", () => {
    expect(item?.permission).toEqual([
      PermissionCodes.DOT_BULK_SELECT,
      PermissionCodes.DOT_TRANSFER_INITIATE,
      PermissionCodes.DOT_HISTORY_VIEW,
    ])
  })
})
