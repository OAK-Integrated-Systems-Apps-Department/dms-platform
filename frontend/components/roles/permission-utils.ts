import type { PermissionItem } from "./types"

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

/** How many of `items`' leaf permissions are present in `selected`. */
export function countSelectedLeafPermissions(
  items: PermissionItem[],
  selected: string[]
): number {
  const selectedSet = new Set(selected)
  return getLeafPermissionIds(items).filter((id) => selectedSet.has(id)).length
}
