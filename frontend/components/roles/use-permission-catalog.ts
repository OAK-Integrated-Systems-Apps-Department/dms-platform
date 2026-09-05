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
  const loading = usePermissionsStore((state) => state.loading)
  const catalogLoaded = usePermissionsStore((state) => state.catalogLoaded)
  const fetchPermissions = usePermissionsStore((state) => state.fetchPermissions)
  const fetchCatalog = usePermissionsStore((state) => state.fetchCatalog)

  useEffect(() => {
    if (permissions.length === 0) fetchPermissions()
    if (!catalogLoaded) fetchCatalog()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const groups = useMemo(
    () => buildPermissionGroups(permissions, modules, actions),
    [permissions, modules, actions]
  )

  return {
    groups,
    loading: (permissions.length === 0 || !catalogLoaded) && loading,
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
  for (const action of [...actions].sort((a, b) => a.name.localeCompare(b.name))) {
    const actionPermissions = permissionsByActionId.get(action.id)
    if (!actionPermissions || actionPermissions.length === 0) continue

    const leaves: PermissionItem[] = actionPermissions
      .map((p) => ({ id: p.code, label: p.name }))
      .sort((a, b) => a.label.localeCompare(b.label))

    const item: PermissionItem =
      leaves.length > 1
        ? {
            id: `${action.moduleCode}.${action.code}`.toLowerCase(),
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
