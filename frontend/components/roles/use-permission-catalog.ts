"use client"

import { useEffect, useMemo } from "react"

import { usePermissions } from "@/lib/auth/use-permissions"
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
 * see the same live catalog, kept up to date by the platform team (see
 * `PermissionController`, which restricts create/update/delete to
 * PLATFORM_ADMIN — there's no in-app admin screen for editing the catalog
 * itself anymore) rather than a frozen snapshot.
 *
 * FIXED BUG: this used to only fetch once per store lifetime
 * (`if (permissions.length === 0) fetchPermissions()`), never again for
 * the rest of the browser session. `usePermissionsStore` persists across
 * every client-side route change, so the moment any one of these four
 * screens had ever been visited once, every later visit — from any of
 * them — reused that same frozen snapshot forever. A permission's
 * module/action regrouping made directly against the backend API in the
 * same session updates the store correctly at the time, but any Role
 * screen that mounts *after* that change with its own fresh
 * `usePermissionCatalog()` call had no way to know a re-fetch was ever
 * needed — leading to a mix of stale and fresh grouping info depending on
 * exactly what had been visited when, which is what produced a permission
 * appearing to sit under both its old and new module at once. This now
 * re-fetches fresh on every mount, unconditionally — still not one
 * request per screen if nothing changed (loading briefly shows cached
 * data, not a blank screen), but it's guaranteed correct.
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
 *
 * Gated on `role.view`/`role.manage` (see `usePermissions`) — the
 * backend's `GET /api/v1/permissions` and `GET /api/v1/permission-actions`
 * require one of those, so calling them for a caller who holds neither
 * just produces a 403 no screen using this hook could ever recover from.
 * Without this, every mount of any of the four screens above re-fired
 * both requests (per the "always re-fetch on mount" fix above) for as
 * long as that caller kept navigating between them, each one landing
 * back on the same 403 — this stops it at the source instead of letting
 * it happen and reacting to the failure.
 */
export function usePermissionCatalog(): UsePermissionCatalogResult {
  const { hasAnyPermission, isLoading: permissionsLoading } = usePermissions()
  const canView = hasAnyPermission("role.view", "role.manage")

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
    //
    // Skipped entirely while `canView` is false: both endpoints below
    // require `role.view` or `role.manage`, so a caller with neither
    // would just get a 403 there's nothing to recover from and no reason
    // to keep asking — see the doc comment above.
    if (canView) {
      fetchPermissions()
      fetchCatalog()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [canView])

  const groups = useMemo(
    () => buildPermissionGroups(permissions, modules, actions),
    [permissions, modules, actions]
  )

  return {
    // True only when there's nothing at all to show yet (first-ever
    // load this session) — once any data is cached, it renders
    // immediately and silently swaps to the fresh copy once the
    // request above resolves, rather than flashing a spinner on every
    // single navigation between these four screens. Also true while
    // `usePermissions()` itself is still resolving, so `canView` isn't
    // trusted until it's actually known one way or the other - and
    // false (not stuck loading) once it resolves to `false`, since
    // there's nothing left to wait for.
    groups,
    loading:
      permissionsLoading ||
      (canView && (permissions.length === 0 || modules.length === 0)),
  }
}

function buildPermissionGroups(
  permissions: PermissionRecord[],
  modules: PermissionModuleRecord[],
  actions: PermissionActionRecord[]
): PermissionGroup[] {
  const permissionsByActionId = new Map<string, PermissionRecord[]>()
  for (const permission of permissions) {
    // Deprecated permissions (the 4 legacy coarse codes - see V15 and
    // `PermissionRecord.deprecated`) are excluded from every Role builder
    // screen that grants NEW permissions. Their granular equivalents
    // (otua.manage-users.*/asm.manage-roles-permissions.*) already cover
    // everything they used to, and V15 backfilled every role that held one
    // of them - so hiding them here loses nothing, it just stops new roles
    // from being built against a code no @authz.hasPermission(...) check
    // reads anymore.
    if (permission.deprecated) continue
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
