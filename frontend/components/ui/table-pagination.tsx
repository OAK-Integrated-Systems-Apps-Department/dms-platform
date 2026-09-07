import { ChevronDown } from "lucide-react"

import {
  Pagination,
  PaginationContent,
  PaginationEllipsis,
  PaginationItem,
  PaginationLink,
  PaginationNext,
  PaginationPrevious,
} from "./pagination"

interface TablePaginationProps {
  currentPage: number
  totalPages: number
  onPageChange: (page: number) => void

  /**
   * Rows-per-page selector, rendered to the left of the page controls.
   * Optional — omit `pageSize`/`onPageSizeChange` (as the Roles list
   * still does) and no selector renders, same as before this was added.
   */
  pageSize?: number
  pageSizeOptions?: number[]
  onPageSizeChange?: (pageSize: number) => void
}

/**
 * The shadcn Pagination primitives render as real <a> elements (via
 * Button's `render` prop), which fits URL-based pagination — but the
 * Users and Roles lists both page through client-side state
 * (`useState`), not `?page=` search params. Every link here uses
 * `href="#"` plus an onClick that calls `event.preventDefault()` before
 * updating that state, rather than converting either page to real
 * navigation. If either list moves to URL-driven pagination later, this
 * component would change to take a `basePath` and drop the onClick
 * handlers in favor of real hrefs.
 *
 * Collapses to first/last + a window around the current page + ellipses
 * once there are more than 7 pages, so it doesn't visually break with
 * large datasets — with small ones (the common case here right now) it
 * just renders every page number.
 */
export function TablePagination({
  currentPage,
  totalPages,
  onPageChange,
  pageSize,
  pageSizeOptions = [5, 10, 25, 50],
  onPageSizeChange,
}: TablePaginationProps) {
  const showPageSizeSelector =
    pageSize !== undefined && onPageSizeChange !== undefined

  // Only the page-size selector matters with a single page of results —
  // still worth keeping visible so rows-per-page stays changeable even
  // when there's nothing to page through yet.
  if (totalPages <= 1 && !showPageSizeSelector) return null

  function go(page: number) {
    if (page >= 1 && page <= totalPages && page !== currentPage) {
      onPageChange(page)
    }
  }

  function linkProps(page: number) {
    return {
      href: "#",
      onClick: (event: React.MouseEvent) => {
        event.preventDefault()
        go(page)
      },
    }
  }

  const pages = buildPageList(currentPage, totalPages)

  return (
    <div className="mt-3.5 flex items-center justify-end gap-2.5">
      {totalPages > 1 && (
        <Pagination className="mx-0 w-auto justify-end">
          <PaginationContent>
            <PaginationItem>
              <PaginationPrevious
                {...linkProps(currentPage - 1)}
                aria-disabled={currentPage === 1}
                className={
                  currentPage === 1
                    ? "pointer-events-none opacity-50"
                    : undefined
                }
              />
            </PaginationItem>

            {pages.map((page, i) =>
              page === "ellipsis" ? (
                <PaginationItem key={`ellipsis-${i}`}>
                  <PaginationEllipsis />
                </PaginationItem>
              ) : (
                <PaginationItem key={page}>
                  <PaginationLink
                    {...linkProps(page)}
                    isActive={page === currentPage}
                  >
                    {page}
                  </PaginationLink>
                </PaginationItem>
              )
            )}

            <PaginationItem>
              <PaginationNext
                {...linkProps(currentPage + 1)}
                aria-disabled={currentPage === totalPages}
                className={
                  currentPage === totalPages
                    ? "pointer-events-none opacity-50"
                    : undefined
                }
              />
            </PaginationItem>
          </PaginationContent>
        </Pagination>
      )}

      {showPageSizeSelector && (
        <div className="relative">
          <label htmlFor="table-page-size" className="sr-only">
            Rows per page
          </label>
          <select
            id="table-page-size"
            value={pageSize}
            onChange={(event) => onPageSizeChange(Number(event.target.value))}
            className="h-[34px] appearance-none rounded-[9px] border border-border bg-card py-0 pr-8 pl-3.5 text-[13px] font-medium text-foreground outline-none focus-visible:border-primary"
          >
            {pageSizeOptions.map((option) => (
              <option key={option} value={option}>
                {option} records
              </option>
            ))}
          </select>
          <ChevronDown
            size={13}
            className="pointer-events-none absolute top-1/2 right-3 -translate-y-1/2 text-[var(--ink-faint)]"
          />
        </div>
      )}
    </div>
  )
}

/** first, last, current ± 1, ellipses filling the gaps — standard shadcn pagination example pattern. */
function buildPageList(
  current: number,
  total: number
): (number | "ellipsis")[] {
  if (total <= 7) {
    return Array.from({ length: total }, (_, i) => i + 1)
  }

  const pages = new Set<number>([1, total, current, current - 1, current + 1])
  const sorted = [...pages]
    .filter((p) => p >= 1 && p <= total)
    .sort((a, b) => a - b)

  const result: (number | "ellipsis")[] = []
  sorted.forEach((page, i) => {
    if (i > 0 && page - sorted[i - 1] > 1) {
      result.push("ellipsis")
    }
    result.push(page)
  })
  return result
}
