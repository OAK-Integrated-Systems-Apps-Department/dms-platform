import { API_BASE_URL } from "@/lib/api-config"
import { apiFetchJson } from "@/lib/api-fetch"

import { apiParams } from "./params"
import type {
  SearchFilterOptionsDto,
  SearchFilters,
  SearchResponseDto,
} from "./types"

const BASE = `${API_BASE_URL}/api/v1/search`

/** `GET /api/v1/search`: one page, with the cursor the previous page returned (null = the first page). */
export function fetchSearch(
  filters: SearchFilters,
  cursor: string | null
): Promise<SearchResponseDto> {
  return apiFetchJson(
    `${BASE}?${apiParams(filters, cursor).toString()}`,
    undefined,
    "Could not search just now."
  )
}

/** `GET /api/v1/search/filters`: the pick-lists for the filter panel. */
export function fetchSearchFilterOptions(): Promise<SearchFilterOptionsDto> {
  return apiFetchJson(`${BASE}/filters`, undefined, "Could not load the search filters.")
}
