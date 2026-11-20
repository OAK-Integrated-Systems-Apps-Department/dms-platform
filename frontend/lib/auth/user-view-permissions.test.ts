import { describe, expect, it } from "vitest"

import { PermissionCodes } from "@/lib/auth/permission-codes"
import { USER_VIEW_PERMISSIONS } from "./user-view-permissions"

// The literal codes dms-core accepts on GET /api/v1/users/{userId} - UserController#getUser's @PreAuthorize
// (hasAnyPermission of OTUA_USERS_VIEW, UPDATE, RESET_PASSWORD, ACTIVATE, DEACTIVATE, DELETE, ASSIGN_ROLE). Written out
// here on purpose, so a rename or an accidental edit of the constant fails this test instead of passing it.
const BACKEND_ACCEPTS = [
  "otua.manage-users.view",
  "otua.manage-users.update",
  "otua.manage-users.reset-password",
  "otua.manage-users.activate",
  "otua.manage-users.deactivate",
  "otua.manage-users.delete",
  "otua.manage-users.assign-role",
]

describe("USER_VIEW_PERMISSIONS", () => {
  it("is exactly the seven permissions the backend accepts on GET /api/v1/users/{id}", () => {
    expect([...USER_VIEW_PERMISSIONS].sort()).toEqual([...BACKEND_ACCEPTS].sort())
    expect(USER_VIEW_PERMISSIONS).toHaveLength(7)
  })

  it("has no duplicates and is built from the catalogue's own codes", () => {
    expect(new Set(USER_VIEW_PERMISSIONS).size).toBe(7)
    for (const code of USER_VIEW_PERMISSIONS) {
      expect(Object.values(PermissionCodes)).toContain(code)
    }
  })

  it("does not include create (the backend does not accept it on that endpoint) or impersonate", () => {
    expect(USER_VIEW_PERMISSIONS).not.toContain(PermissionCodes.OTUA_USERS_CREATE)
    expect(USER_VIEW_PERMISSIONS).not.toContain(PermissionCodes.OTUA_USERS_IMPERSONATE)
  })
})