"use client"

import { useState } from "react"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { ExportJobsTable } from "@/components/export-jobs/export-jobs-table"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { BackendUnavailable } from "@/components/ui/backend-unavailable"
import { LoadingState } from "@/components/ui/loading-state"
import { SearchableSelect } from "@/components/ui/searchable-select"
import { TablePagination } from "@/components/ui/table-pagination"
import { isNetworkError } from "@/lib/api-error"
import { EXPORT_JOB_STATUS_LABELS } from "@/lib/export-jobs/export-job-badges"
import { useMyExportJobs } from "@/lib/export-jobs/use-my-export-jobs"
import { usePageTitle } from "@/lib/use-page-title"

import type { ExportJobStatus } from "@/lib/export-jobs/api"

const DEFAULT_PAGE_SIZE = 25

const STATUS_OPTIONS = (Object.keys(EXPORT_JOB_STATUS_LABELS) as ExportJobStatus[]).map((status) => ({
  value: status,
  label: EXPORT_JOB_STATUS_LABELS[status],
}))

/**
 * My Exports - the signed-in user's own bulk export jobs (Module 2.1),
 * backed by `GET /api/v1/export-jobs`, which scopes to the caller's tenant
 * and user server-side. No PermissionGate: every user may see (and, with
 * document access, download) the exports they requested themselves;
 * starting one is the Bulk Export page.
 */
export function ExportsContent() {
  usePageTitle("My Exports")

  const [status, setStatus] = useState<"" | ExportJobStatus>("")
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE)

  const { data, isPending, isError, error, refetch } = useMyExportJobs(status || undefined, page, pageSize)

  return (
    <DashboardShell initialActiveLabel="My Exports">
      <AdminBreadcrumb className="mb-3" segments={[{ label: "Dashboard", href: "/" }, { label: "My Exports" }]} />
      <div className="mb-[22px] max-w-[680px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">My Exports</h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Bulk exports you have requested and where each one stands. Finished exports are kept until their
          &ldquo;available until&rdquo; time and then removed automatically.
        </p>
      </div>

      <div className="mb-4 flex flex-wrap items-center gap-2.5">
        <SearchableSelect
          allLabel="All statuses"
          searchable={false}
          selected={status}
          onChange={(value) => {
            setStatus(value as "" | ExportJobStatus)
            setPage(1)
          }}
          options={STATUS_OPTIONS}
          className="w-[170px]"
        />
      </div>

      {isPending ? (
        <LoadingState message="Loading your exports…" />
      ) : isError ? (
        isNetworkError(error) ? (
          <BackendUnavailable onRetry={refetch} />
        ) : (
          <div className="rounded-lg border border-[var(--rust)] bg-[var(--rust-tint)] p-[26px] text-[13.5px] text-[var(--rust)] shadow-[var(--ledger-shadow)]">
            {error instanceof Error ? error.message : "Could not load your exports."}
          </div>
        )
      ) : (
        <>
          <ExportJobsTable rows={data?.content ?? []} filtered={Boolean(status)} />
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
    </DashboardShell>
  )
}
