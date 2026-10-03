"use client"

import { useInfiniteQuery } from "@tanstack/react-query"

import { fetchBulkImports, type BulkImportSummaryDto } from "./api"
import { RECENT_BULK_IMPORTS_KEY } from "./use-bulk-import"

/** Rows per "Load more". */
export const RECENT_IMPORTS_PAGE_SIZE = 10

/**
 * The caller's bulk imports, newest first, one page of 10 at a time. Keyed
 * under `["bulk-imports"]`, so creating, submitting, cancelling and starting
 * over (which all invalidate that prefix) refresh it. `useBulkImport`
 * refreshes it when a watched batch finishes. A refresh re-reads every page
 * loaded so far.
 */
export function useRecentBulkImports() {
  const query = useInfiniteQuery({
    queryKey: RECENT_BULK_IMPORTS_KEY,
    queryFn: ({ pageParam }) => fetchBulkImports(pageParam, RECENT_IMPORTS_PAGE_SIZE),
    initialPageParam: 0,
    getNextPageParam: (lastPage) => (lastPage.last ? undefined : lastPage.page + 1),
    retry: false,
  })

  // A batch created between "Load more" clicks shifts the pages by one, so
  // the same row can arrive twice - keep the first.
  const seen = new Set<string>()
  const imports: BulkImportSummaryDto[] = []
  for (const page of query.data?.pages ?? []) {
    for (const row of page.content) {
      if (seen.has(row.batchId)) continue
      seen.add(row.batchId)
      imports.push(row)
    }
  }

  return { ...query, imports }
}
