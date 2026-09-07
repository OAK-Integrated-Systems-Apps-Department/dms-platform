"use client"

import { useEffect, useMemo } from "react"

import { usePermissionsStore } from "@/stores/permissions-store"
import type {
  PermissionActionRecord,
  PermissionModuleRecord,
  PermissionRecord,
} from "@/components/permissions/types"

import type { PermissionGroup, PermissionItem } from "./types"

interface UsePermissionCatalogResult {
  groups: PermissionGroup[]
  loading: boolean
}

/**
 * Builds the same `PermissionGroup[]` shape `components/roles/data.ts`'s
 * hardcoded `permissionGroups` used to provide, but derived from the real
 * permission catalog (`usePermissionsStore`, backed by
 * `V10__seed_fine_grained_permissions.sql`) instead. Every screen that
 * used to import the static `permissionGroups` — Add/Edit Role's inline
 * picker, Role Permission Configuration, the Permission Matrix, and Role
 * Details' permission summary — now calls this hook instead, so all four
 * see the same live catalog, kept up to date by the Permission Catalog
 * admin screens (`/admin/permissions`) rather than a frozen snapshot.
 *
 * FIXED BUG: this used to only fetch once per store lifetime
 * (`if (permissions.length === 0) fetchPermissions()`), never again for
 * the rest of the browser session. `usePermissionsStore` persists across
 * every client-side route change, so the moment any one of these four
 * screens had ever been visited once, every later visit — from any of
 * them — reused that same frozen snapshot forever. Editing a permission's
 * module/action via `/admin/permissions/{id}/edit` in the same session
 * updates the store correctly at the time, but any Role screen that
 * mounts *after* that edit with its own fresh `usePermissionCatalog()`
 * call had no way to know a re-fetch was ever needed — leading to a mix
 * of stale and fresh grouping info depending on exactly what had been
 * visited when, which is what produced a permission appearing to sit
 * under both its old and new module at once. This now re-fetches fresh
 * on every mount, unconditionally — still not one request per screen if
 * nothing changed (loading briefly shows cached data, not a blank
 * screen), but it's guaranteed correct.
 *
 * Grouping: permissions are grouped by `actionId`, and actions by
 * `moduleCode`, mirroring the DB's actual Module -> Action -> Permission
 * hierarchy (`Permission.java`). An action with more than one permission
 * becomes a parent `PermissionItem` — its own id is a synthetic,
 * not-grantable grouping key (`${moduleCode}.${actionCode}`, lowercased),
 * used only for React keys and collapsible open-state, same role the old
 * hardcoded ids like `"asm.manage-roles-permissions"` played. An action
 * with exactly one permission is flattened to a single leaf directly
 * under the module, matching how the original catalog kept single-step
 * actions like "Search Documents" ungrouped rather than wrapping them in
 * a redundant one-item subsection.
 *
 * Modules with no actions that have any permissions yet (and actions with
 * no permissions yet) are omitted rather than shown as empty groups —
 * there's nothing to grant under them.
 */
export function usePermissionCatalog(): UsePermissionCatalogResult {
  const permissions = usePermissionsStore((state) => state.permissions)
  const modules = usePermissionsStore((state) => state.modules)
  const actions = usePermissionsStore((state) => state.actions)
  const fetchPermissions = usePermissionsStore(
    (state) => state.fetchPermissions
  )
  const fetchCatalog = usePermissionsStore((state) => state.fetchCatalog)

  useEffect(() => {
    // Always re-fetch on mount, regardless of whether a cached copy
    // already exists in the store — see the doc comment above for why
    // "fetch once ever" was wrong here. Whatever's already cached still
    // renders immediately in the meantime (see `loading` below), so this
    // doesn't blank the screen while the fresh data comes in.
    fetchPermissions()
    fetchCatalog()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const groups = useMemo(
    () => buildPermissionGroups(permissions, modules, actions),
    [permissions, modules, actions]
  )

  return {
    // True only when there's nothing at all to show yet (first-ever
    // load this session) — once any data is cached, it renders
    // immediately and silently swaps to the fresh copy once the
    // request above resolves, rather than flashing a spinner on every
    // single navigation between these four screens.
    groups,
    loading: permissions.length === 0 || modules.length === 0,
  }
}

function buildPermissionGroups(
  permissions: PermissionRecord[],
  modules: PermissionModuleRecord[],
  actions: PermissionActionRecord[]
): PermissionGroup[] {
  const permissionsByActionId = new Map<string, PermissionRecord[]>()
  for (const permission of permissions) {
    const list = permissionsByActionId.get(permission.actionId) ?? []
    list.push(permission)
    permissionsByActionId.set(permission.actionId, list)
  }

  const itemsByModuleCode = new Map<string, PermissionItem[]>()
  for (const action of [...actions].sort((a, b) =>
    a.name.localeCompare(b.name)
  )) {
    const actionPermissions = permissionsByActionId.get(action.id)
    if (!actionPermissions || actionPermissions.length === 0) continue

    const leaves: PermissionItem[] = actionPermissions
      .map((p) => ({ id: p.code, label: p.name }))
      .sort((a, b) => a.label.localeCompare(b.label))

    const item: PermissionItem =
      leaves.length > 1
        ? {
            // Was `${moduleCode}.${actionCode}`.toLowerCase() — which, for
            // any action whose module/action-code pair happens to spell
            // out an existing permission_code (e.g. USER/VIEW → "user.view",
            // which collides with the actual seeded permission of that
            // exact code once any second permission — like a custom
            // "test.tst" — is also filed under that same action), produced
            // a group id *identical* to one of its own children's leaf id.
            // React uses these ids as list keys; two nodes sharing a key
            // in the same tree makes reconciliation unreliable, especially
            // across a re-render triggered by editing a permission's
            // module/action (which changes this tree's shape and sort
            // order) — showing stale/duplicated content instead of
            // cleanly replacing it. `action.id` is a real UUID and can
            // never equal a dot-separated permission code, so it can't
            // collide with any leaf under it, no matter how many
            // permissions end up sharing one action.
            id: `group:${action.id}`,
            label: action.name,
            permissions: leaves,
          }
        : leaves[0]

    const list = itemsByModuleCode.get(action.moduleCode) ?? []
    list.push(item)
    itemsByModuleCode.set(action.moduleCode, list)
  }

  // `modules` (see stores/permissions-store.ts) is already sorted
  // alphabetically by name, so mapping over it here (rather than over the
  // Map's own insertion order) gives a stable, predictable group order.
  return modules
    .filter((m) => itemsByModuleCode.has(m.code))
    .map((m) => ({
      module: m.name,
      moduleCode: m.code,
      permissions: itemsByModuleCode.get(m.code)!,
    }))
}
