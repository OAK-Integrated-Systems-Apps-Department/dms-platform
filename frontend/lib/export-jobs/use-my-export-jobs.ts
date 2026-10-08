"use client"

import { keepPreviousData, useQuery } from "@tanstack/react-query"

import { fetchMyExportJobs, isExportInFlight } from "./api"
import type { ExportJobStatus } from "./api"

/** How often the list refreshes while one of its exports is still being prepared. */
const IN_FLIGHT_POLL_MS = 4000

/**
 * One page of the caller's exports. `page` is 1-indexed here
 * (TablePagination), 0-indexed on the wire. Polls while any export on the
 * page is queued or being prepared, and stops once they've all finished.
 */
export function useMyExportJobs(status: ExportJobStatus | undefined, page: number, size: number) {
  return useQuery({
    queryKey: ["export-jobs", "mine", status ?? "all", page, size],
    queryFn: () => fetchMyExportJobs(status, page - 1, size),
    placeholderData: keepPreviousData,
    refetchInterval: (query) =>
      query.state.data?.content.some((job) => isExportInFlight(job.status)) ? IN_FLIGHT_POLL_MS : false,
  })
}
