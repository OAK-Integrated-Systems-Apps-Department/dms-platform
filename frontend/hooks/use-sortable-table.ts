import * as React from "react"

export type SortDirection = "asc" | "desc"

/**
 * Generic client-side sorting for admin tables (Users, Roles, Role
 * Details' Assigned Users, …). Each table defines its own `SortKey`
 * union and an accessor per key that pulls the comparable value off a
 * row; this hook handles the click-to-toggle direction state and the
 * actual comparison.
 *
 * Comparison is numeric when the accessor returns a number for both
 * sides, otherwise a locale-aware, case-insensitive string compare —
 * covers every column currently in use (names, statuses, counts,
 * dates-as-strings) without each table re-implementing a comparator.
 *
 * Returns a new sorted array rather than mutating `items`, so it's
 * safe to sort the result of a `useMemo`/store selector.
 */
export function useSortableTable<T, K extends string>(
  items: T[],
  accessors: Record<K, (item: T) => string | number>,
  initial?: { key: K; direction: SortDirection }
) {
  const [sortKey, setSortKey] = React.useState<K | null>(initial?.key ?? null)
  const [direction, setDirection] = React.useState<SortDirection>(
    initial?.direction ?? "asc"
  )

  const sorted = React.useMemo(() => {
    if (!sortKey) return items
    const accessor = accessors[sortKey]
    return [...items].sort((a, b) => {
      const aValue = accessor(a)
      const bValue = accessor(b)
      const comparison =
        typeof aValue === "number" && typeof bValue === "number"
          ? aValue - bValue
          : String(aValue).localeCompare(String(bValue), undefined, {
              sensitivity: "base",
              numeric: true,
            })
      return direction === "asc" ? comparison : -comparison
    })
  }, [items, sortKey, direction, accessors])

  function toggleSort(key: K) {
    if (key === sortKey) {
      setDirection((current) => (current === "asc" ? "desc" : "asc"))
    } else {
      setSortKey(key)
      setDirection("asc")
    }
  }

  return { sorted, sortKey, direction, toggleSort }
}