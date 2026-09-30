"use client"

import { useQuery } from "@tanstack/react-query"

import { fetchBulkImportFailures } from "./api"

/**
 * The failed rows of a finished batch. A finished batch's rows don't
 * change, so this is fetched once (`staleTime: Infinity`) and keyed outside
 * `["bulk-imports"]`, so neither the status poll nor batch mutations,
 * which invalidate that prefix, refetch it.
 */
export function useBulkImportFailures(batchId: string, enabled = true) {
  const query = useQuery({
    queryKey: ["bulk-import-failures", batchId],
    queryFn: () => fetchBulkImportFailures(batchId),
    retry: false,
    staleTime: Infinity,
    enabled,
  })
  return { ...query, failures: query.data ?? [] }
}
