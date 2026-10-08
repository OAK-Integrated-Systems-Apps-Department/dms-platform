"use client"

import { keepPreviousData, useQuery } from "@tanstack/react-query"

import { fetchQuarantineRecords } from "./api"
import type { QuarantineFilters } from "./api"

/**
 * Server-driven list, like `useAuditEvents`: every filter/page/size change
 * re-queries `GET /api/v1/quarantine`. `page` is 1-indexed (matching
 * `TablePagination`) and converted to the backend's 0-indexed page here.
 */
export function useQuarantineRecords(filters: QuarantineFilters, page: number, size: number) {
  return useQuery({
    queryKey: ["quarantine", filters, page, size],
    queryFn: () => fetchQuarantineRecords(filters, page - 1, size),
    placeholderData: keepPreviousData,
  })
}
