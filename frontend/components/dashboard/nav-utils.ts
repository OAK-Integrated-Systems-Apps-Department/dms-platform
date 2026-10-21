import type { NavGroup, NavItem, NavSection } from "./types"

/** A section after filtering: `groups` is always present, possibly empty. */
export type VisibleNavSection = NavSection & { groups: NavGroup[] }

/**
 * Keeps the items `isVisible` accepts, then drops any group or section left
 * with nothing in it, so a user who can see no Administration screens never
 * sees the Administration heading either.
 */
export function filterNavSections(
  sections: NavSection[],
  isVisible: (item: NavItem) => boolean
): VisibleNavSection[] {
  return sections
    .map((section) => ({
      ...section,
      items: section.items.filter(isVisible),
      groups: (section.groups ?? [])
        .map((group) => ({ ...group, items: group.items.filter(isVisible) }))
        .filter((group) => group.items.length > 0),
    }))
    .filter((section) => section.items.length > 0 || section.groups.length > 0)
}

function matches(href: string, pathname: string): boolean {
  if (href === "/") return pathname === "/"
  return pathname === href || pathname.startsWith(`${href}/`)
}

/**
 * The one href that should read as active for `pathname`: the longest match,
 * so /documents/scan highlights "Scan documents" only, not "My Documents"
 * as well. Matching is on path segments, so /pinned-x is not under /pinned.
 */
export function findActiveHref(
  sections: NavSection[],
  pathname: string
): string | undefined {
  let best: string | undefined
  for (const section of sections) {
    const items = [
      ...section.items,
      ...(section.groups ?? []).flatMap((group) => group.items),
    ]
    for (const { href } of items) {
      if (href && matches(href, pathname) && href.length > (best?.length ?? -1)) {
        best = href
      }
    }
  }
  return best
}

export function groupHasHref(group: NavGroup, href: string | undefined) {
  return href !== undefined && group.items.some((item) => item.href === href)
}
