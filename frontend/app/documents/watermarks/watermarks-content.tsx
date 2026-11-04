"use client"

import { LoadFailed } from "@/components/ui/load-failed"
import { useState } from "react"

import { DocumentsHub } from "@/components/documents/documents-hub"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { WatermarkJobsTable } from "@/components/watermarks/watermark-jobs-table"
import { BackendUnavailable } from "@/components/ui/backend-unavailable"
import { LoadingState } from "@/components/ui/loading-state"
import { SearchableSelect } from "@/components/ui/searchable-select"
import { TablePagination } from "@/components/ui/table-pagination"
import { isNetworkError } from "@/lib/api-error"
import { usePageTitle } from "@/lib/use-page-title"
import { WATERMARK_JOB_STATUS_LABELS } from "@/lib/watermarks/watermark-badges"
import { useMyWatermarkJobs } from "@/lib/watermarks/use-watermark-jobs"
import { useWatermarkAvailability } from "@/lib/watermarks/use-watermark-availability"

import type { WatermarkJobStatus } from "@/lib/watermarks/api"

const DEFAULT_PAGE_SIZE = 25

const STATUS_OPTIONS = (
  Object.keys(WATERMARK_JOB_STATUS_LABELS) as WatermarkJobStatus[]
).map((status) => ({
  value: status,
  label: WATERMARK_JOB_STATUS_LABELS[status],
}))

/**
 * My Watermarked Copies - the signed-in user's own watermark jobs, backed
 * by `GET /api/v1/watermark-jobs`, which scopes to the caller's tenant AND
 * user server-side.
 *
 * No PermissionGate, matching My Exports: every user may see the copies
 * they asked for themselves. Losing document access still ends access to
 * the copies, because the DOWNLOAD re-checks it - gating the list as well
 * would hide a user's own history from them for no gain.
 *
 * A direct visit while the feature is switched off gets an explanation
 * rather than a stack of failed queries: the tab is hidden in that case,
 * but a bookmark still works.
 */
export function WatermarksContent() {
  usePageTitle("My Watermarked Copies")

  const [status, setStatus] = useState<"" | WatermarkJobStatus>("")
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE)

  const availability = useWatermarkAvailability()
  const featureOff = availability.data?.present === false

  const { data, isPending, isError, error, refetch } = useMyWatermarkJobs(
    status || undefined,
    page,
    pageSize
  )

  return (
    <DashboardShell initialActiveLabel="My Documents">
      <DocumentsHub active="watermarks">
        {featureOff ? (
          <div className="rounded-lg border border-border bg-card p-[26px] text-[13.5px] text-muted-foreground shadow-[var(--ledger-shadow)]">
            Watermarking isn&apos;t switched on for this workspace.
          </div>
        ) : (
          <>
            <div className="mb-4 flex flex-wrap items-center gap-2.5">
              <SearchableSelect
                allLabel="All statuses"
                searchable={false}
                selected={status}
                onChange={(value) => {
                  setStatus(value as "" | WatermarkJobStatus)
                  setPage(1)
                }}
                options={STATUS_OPTIONS}
                className="w-[170px]"
              />
            </div>

            {isPending ? (
              <LoadingState message="Loading your watermarked copies…" />
            ) : isError ? (
              isNetworkError(error) ? (
                <BackendUnavailable onRetry={refetch} />
              ) : (
                <LoadFailed error={error} fallback="Could not load your watermarked copies." />
              )
            ) : (
              <>
                <WatermarkJobsTable
                  rows={data?.content ?? []}
                  filtered={status !== ""}
                />
                <TablePagination
                  currentPage={page}
                  totalPages={data?.totalPages ?? 1}
                  onPageChange={setPage}
                  pageSize={pageSize}
                  onPageSizeChange={(size) => {
                    setPageSize(size)
                    setPage(1)
                  }}
                />
              </>
            )}
          </>
        )}
      </DocumentsHub>
    </DashboardShell>
  )
}
