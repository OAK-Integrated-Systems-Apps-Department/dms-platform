// @vitest-environment node
import { readdirSync, readFileSync, statSync } from "node:fs"
import { join } from "node:path"
import { describe, expect, it } from "vitest"

import { AUDIT_ACTION_CATALOG } from "@/lib/audit-events/audit-action-catalog"
import { PermissionCodes } from "@/lib/auth/permission-codes"

const DSR_ACTIONS = [
  "SEARCH_REINDEX_REQUESTED",
  "SEARCH_PRESET_CREATED",
  "SEARCH_PRESET_UPDATED",
  "SEARCH_PRESET_DELETED",
  "SEARCH_PRESET_LIMIT_CHANGED",
  "SEARCH_RECONCILE_REQUESTED",
]

describe("audit action mirror", () => {
  it.each(DSR_ACTIONS)("has %s in module DSR", (action) => {
    const entry = AUDIT_ACTION_CATALOG.find((e) => e.action === action)
    expect(entry?.module).toBe("DSR")
    expect(entry?.label).toBeTruthy()
  })

  it("has no duplicate actions", () => {
    const names = AUDIT_ACTION_CATALOG.map((e) => e.action)
    expect(new Set(names).size).toBe(names.length)
  })
})

describe("preset permission codes", () => {
  it("are the exact strings the backend seeded", () => {
    expect(PermissionCodes.DSR_MANAGE_PRESETS_CREATE).toBe("dsr.manage-saved-search-presets.create")
    expect(PermissionCodes.DSR_MANAGE_PRESETS_UPDATE).toBe("dsr.manage-saved-search-presets.update")
    expect(PermissionCodes.DSR_MANAGE_PRESETS_DELETE).toBe("dsr.manage-saved-search-presets.delete")
    expect(PermissionCodes.DSR_MANAGE_PRESETS_SHARE).toBe("dsr.manage-saved-search-presets.share")
  })

  it("the share code is declared (and listed in the role editor's catalog of every permission) but no feature uses it", () => {
    const root = join(__dirname, "..", "..")
    const hits: string[] = []
    const walk = (dir: string) => {
      for (const name of readdirSync(dir)) {
        if (["node_modules", ".next", ".git", "coverage"].includes(name)) continue
        const path = join(dir, name)
        if (statSync(path).isDirectory()) walk(path)
        else if (/\.(ts|tsx)$/.test(name) && !/phase2-catalogs\.test\.ts$/.test(name)) {
          const text = readFileSync(path, "utf8")
          if (text.includes("DSR_MANAGE_PRESETS_SHARE") || text.includes("manage-saved-search-presets.share")) {
            hits.push(path.slice(root.length + 1))
          }
        }
      }
    }
    walk(root)
    // permission-codes.ts declares it; components/roles/data.ts is the role editor's list of ALL permissions (it lists,
    // it does not check). Any other file here would be a feature using a permission that must stay unused.
    expect(hits.sort()).toEqual(["components/roles/data.ts", "lib/auth/permission-codes.ts"])
  })
})
