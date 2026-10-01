"use client"

import { useQuery } from "@tanstack/react-query"

import { fetchBulkImportRowCheck } from "./api"

/**
 * The template's rows with problems, checked while the batch is still
 * uploading. The rows' values never change after the template is
 * uploaded (and missing files aren't reported here), so this is fetched
 * once per batch and kept outside the ["bulk-imports"] prefix the status
 * poll invalidates.
 */
export function useBulkImportRowCheck(batchId: string) {
  const query = useQuery({
    queryKey: ["bulk-import-row-check", batchId],
    queryFn: () => fetchBulkImportRowCheck(batchId),
    retry: false,
    staleTime: Infinity,
  })
  return { ...query, problems: query.data ?? [] }
}
