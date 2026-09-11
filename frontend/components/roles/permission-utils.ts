import type { PermissionGroup, PermissionItem } from "./types"

/**
 * Flattens a permission list down to just the grantable leaf items — i.e.
 * items with no nested `permissions`. An item that HAS nested permissions
 * (e.g. the "Manage Users" action) is a grouping label, not a grantable id
 * itself, so it's expanded into its children instead of being included
 * directly. Shared by the permission matrix, role permission config, and
 * role form so all three treat nesting the same way.
 */
export function getLeafPermissions(items: PermissionItem[]): PermissionItem[] {
  return items.flatMap((item) =>
    item.permissions && item.permissions.length > 0
      ? getLeafPermissions(item.permissions)
      : [item]
  )
}

export function getLeafPermissionIds(items: PermissionItem[]): string[] {
  return getLeafPermissions(items).map((item) => item.id)
}

/**
 * Recursively keeps only permission items whose label matches `query`
 * (case-insensitive), plus any ancestor of a match so the result stays a
 * valid tree instead of orphaning nested items. If a parent action's own
 * label matches (e.g. searching "manage users"), its children are kept
 * in full rather than re-filtered — matching the parent is treated as
 * "show everything under it", same intuition as matching a module name.
 * Shared by the Permission Matrix and the Add/Edit Role quick-list, so
 * "find a specific permission quickly" behaves identically in both.
 */
export function filterPermissionItems(
  items: PermissionItem[],
  query: string
): PermissionItem[] {
  const q = query.trim().toLowerCase()
  if (!q) return items

  return items.reduce<PermissionItem[]>((acc, item) => {
    const hasChildren = item.permissions && item.permissions.length > 0
    const selfMatches = item.label.toLowerCase().includes(q)

    if (hasChildren) {
      const filteredChildren = filterPermissionItems(item.permissions!, q)
      if (selfMatches || filteredChildren.length > 0) {
        acc.push({
          ...item,
          permissions: selfMatches ? item.permissions : filteredChildren,
        })
      }
    } else if (selfMatches) {
      acc.push(item)
    }
    return acc
  }, [])
}

/**
 * Same idea as `filterPermissionItems`, one level up: keeps a whole module
 * group if its name/code matches, or if any of its permissions (searched
 * recursively) match.
 */
export function filterPermissionGroups(
  groups: PermissionGroup[],
  query: string
): PermissionGroup[] {
  const q = query.trim().toLowerCase()
  if (!q) return groups

  return groups.reduce<PermissionGroup[]>((acc, group) => {
    const moduleMatches =
      group.module.toLowerCase().includes(q) ||
      group.moduleCode.toLowerCase().includes(q)
    const filteredPermissions = filterPermissionItems(group.permissions, q)

    if (moduleMatches || filteredPermissions.length > 0) {
      acc.push({
        ...group,
        permissions: moduleMatches ? group.permissions : filteredPermissions,
      })
    }
    return acc
  }, [])
}

/** How many of `items`' leaf permissions are present in `selected`. */
export function countSelectedLeafPermissions(
  items: PermissionItem[],
  selected: string[]
): number {
  const selectedSet = new Set(selected)
  return getLeafPermissionIds(items).filter((id) => selectedSet.has(id)).length
}