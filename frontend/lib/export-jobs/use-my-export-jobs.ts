"use client"

import { keepPreviousData, useQuery } from "@tanstack/react-query"

import { fetchMyExportJobs } from "./api"
import type { ExportJobStatus } from "./api"

/** One page of the caller's exports. `page` is 1-indexed here (TablePagination), 0-indexed on the wire. */
export function useMyExportJobs(status: ExportJobStatus | undefined, page: number, size: number) {
  return useQuery({
    queryKey: ["export-jobs", "mine", status ?? "all", page, size],
    queryFn: () => fetchMyExportJobs(status, page - 1, size),
    placeholderData: keepPreviousData,
  })
}
