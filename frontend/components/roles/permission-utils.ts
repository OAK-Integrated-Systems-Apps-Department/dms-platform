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

/** How many of `items`' leaf permissions are present in `selected`. */
export function countSelectedLeafPermissions(
  items: PermissionItem[],
  selected: string[]
): number {
  const selectedSet = new Set(selected)
  return getLeafPermissionIds(items).filter((id) => selectedSet.has(id)).length
}

/**
 * Recursively keeps only modules/actions/permissions matching `query`
 * (case-insensitive substring, against the module name or a permission's
 * label) — a module or action match keeps every one of its children, an
 * unmatched parent keeps only its matching children. Returns `groups`
 * unchanged when `query` is blank. Shared by `PermissionPicker` and
 * `UserAccessSummary` so both search the exact same way; pure function of
 * `(groups, query)` only — no dependency on selection/interaction state,
 * which is what makes it safe to reuse across an editable picker and a
 * read-only summary.
 */
export function filterPermissionGroups(
  groups: PermissionGroup[],
  query: string
): PermissionGroup[] {
  const q = query.trim().toLowerCase()
  if (!q) return groups
  return groups
    .map((group) => {
      const moduleMatches = group.module.toLowerCase().includes(q)
      const permissions = group.permissions
        .map((permission): PermissionItem | null => {
          if (permission.permissions && permission.permissions.length > 0) {
            const children =
              moduleMatches || permission.label.toLowerCase().includes(q)
                ? permission.permissions
                : permission.permissions.filter((child) =>
                    child.label.toLowerCase().includes(q)
                  )
            return children.length > 0
              ? { ...permission, permissions: children }
              : null
          }
          return moduleMatches || permission.label.toLowerCase().includes(q)
            ? permission
            : null
        })
        .filter(
          (permission): permission is PermissionItem => permission !== null
        )
      return { ...group, permissions }
    })
    .filter((group) => group.permissions.length > 0)
}
