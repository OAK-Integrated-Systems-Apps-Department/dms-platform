"use client"

import { keepPreviousData, useQuery } from "@tanstack/react-query"

import { fetchSearch, fetchSearchFilterOptions } from "./api"
import { searchSignature } from "./params"
import type { SearchFilters } from "./types"

/**
 * One page of results. `placeholderData` keeps the previous page on screen while the next one loads, so paging and
 * changing a filter do not flash empty.
 */
export function useSearch(filters: SearchFilters, cursor: string | null, enabled = true) {
  return useQuery({
    queryKey: ["search", searchSignature(filters), filters.sort, filters.dir, cursor],
    queryFn: () => fetchSearch(filters, cursor),
    enabled,
    staleTime: 15_000,
    retry: false,
    placeholderData: keepPreviousData,
  })
}

/** The filter panel's pick-lists; they change rarely, so they are kept for a minute. */
export function useSearchFilterOptions(enabled = true) {
  return useQuery({
    queryKey: ["search", "filter-options"],
    queryFn: fetchSearchFilterOptions,
    enabled,
    staleTime: 60_000,
    retry: false,
  })
}
