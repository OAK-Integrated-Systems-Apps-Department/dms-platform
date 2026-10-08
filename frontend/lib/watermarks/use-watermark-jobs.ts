"use client"

import { keepPreviousData, useQuery } from "@tanstack/react-query"

import { fetchMyWatermarkJobs, fetchWatermarkJob, isWatermarkInFlight } from "./api"

import type { WatermarkJobStatus } from "./api"

/** First polling interval while a copy is being produced. */
const POLL_START_MS = 2000
/** Backed off to this once a job has been running a while. */
const POLL_MAX_MS = 15000
/** How often the LIST refreshes while any row on it is still in flight. */
const LIST_POLL_MS = 4000

/**
 * Polls faster at first and then slower, rather than every 2 s forever.
 *
 * Almost every watermark finishes inside two seconds (a 25 MB Word document
 * takes about 0.7 s on the server), so the first few polls are what
 * actually matter and should be quick. The slow tail is the exception - a
 * large spreadsheet, or a queue - and hammering the API every 2 s for a
 * minute to watch it is wasted traffic on every open tab. Doubling to a 15 s
 * ceiling keeps the common case instant and the rare case cheap.
 */
function backOff(attempt: number): number {
  return Math.min(POLL_START_MS * 2 ** Math.max(0, attempt - 3), POLL_MAX_MS)
}

/**
 * One watermark job, polled until it reaches a terminal state and then left
 * alone. Pass `null` to track nothing (the dialog before submit).
 */
export function useWatermarkJob(jobId: string | null) {
  return useQuery({
    queryKey: ["watermarks", "job", jobId],
    enabled: jobId !== null,
    queryFn: () => fetchWatermarkJob(jobId as string),
    refetchInterval: (query) => {
      const status = query.state.data?.status
      // No data yet counts as in flight - the first poll has to happen.
      if (status && !isWatermarkInFlight(status)) return false
      return backOff(query.state.dataUpdateCount)
    },
  })
}

/**
 * One page of the caller's own watermarked copies. `page` is 1-indexed here
 * (TablePagination), 0-indexed on the wire - same convention as
 * `useMyExportJobs`. Polls while any row is still being produced and stops
 * once they have all settled.
 */
export function useMyWatermarkJobs(
  status: WatermarkJobStatus | undefined,
  page: number,
  size: number
) {
  return useQuery({
    queryKey: ["watermarks", "mine", status ?? "all", page, size],
    queryFn: () => fetchMyWatermarkJobs(status, page - 1, size),
    placeholderData: keepPreviousData,
    refetchInterval: (query) =>
      query.state.data?.content.some((job) => isWatermarkInFlight(job.status))
        ? LIST_POLL_MS
        : false,
  })
}
