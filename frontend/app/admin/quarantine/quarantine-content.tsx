"use client"

import { useMemo, useState } from "react"
import { useRouter } from "next/navigation"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import {
  EMPTY_QUARANTINE_FILTERS,
  QuarantineFilters,
  type QuarantineFilterState,
} from "@/components/quarantine/quarantine-filters"
import { QuarantineTable } from "@/components/quarantine/quarantine-table"
import { BackendUnavailable } from "@/components/ui/backend-unavailable"
import { LoadingState } from "@/components/ui/loading-state"
import { TablePagination } from "@/components/ui/table-pagination"
import { isNetworkError } from "@/lib/api-error"
import { QUARANTINE_ANY_PERMISSION } from "@/lib/quarantine/permissions"
import { useQuarantineRecords } from "@/lib/quarantine/use-quarantine-records"
import { usePageTitle } from "@/lib/use-page-title"

import type { QuarantineFilters as QuarantineApiFilters, QuarantineRecordDto } from "@/lib/quarantine/api"

const DEFAULT_PAGE_SIZE = 25

/**
 * Quarantine - files the malware scan blocked (VMSBDI FR-005), backed by
 * `GET /api/v1/quarantine` (server-side filtering/pagination, like the
 * Audit Log). Rows open the detail page, where release and delete live.
 * Quarantined files can never be downloaded or previewed from here.
 */
export function QuarantineContent() {
  usePageTitle("Quarantine")

  const router = useRouter()
  const [filters, setFilters] = useState<QuarantineFilterState>(EMPTY_QUARANTINE_FILTERS)
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE)

  function handleFiltersChange(update: Partial<QuarantineFilterState>) {
    setFilters((current) => ({ ...current, ...update }))
    setPage(1)
  }

  function handlePageSizeChange(size: number) {
    setPageSize(size)
    setPage(1)
  }

  // Same ":00Z" suffix as the Audit Log - the backend binds an OffsetDateTime,
  // which rejects a date-time with no offset.
  const apiFilters: QuarantineApiFilters = useMemo(
    () => ({
      search: filters.search.trim() || undefined,
      status: filters.status || undefined,
      detectedFrom: filters.detectedFrom ? `${filters.detectedFrom}:00Z` : undefined,
      detectedTo: filters.detectedTo ? `${filters.detectedTo}:00Z` : undefined,
    }),
    [filters]
  )

  const { data, isPending, isError, error, refetch } = useQuarantineRecords(apiFilters, page, pageSize)

  function handleView(row: QuarantineRecordDto) {
    router.push(`/admin/quarantine/${row.quarantineId}`)
  }

  return (
    <DashboardShell initialActiveLabel="Quarantine">
      <AdminBreadcrumb className="mb-3" segments={[{ label: "Dashboard", href: "/" }, { label: "Quarantine" }]} />
      <div className="mb-[22px] max-w-[680px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">Quarantine</h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Uploads the malware scan blocked. Nothing here ever reached the document repository. Review each
          file, release a confirmed false positive, or delete it permanently.
        </p>
      </div>

      <PermissionGate permission={QUARANTINE_ANY_PERMISSION}>
        <QuarantineFilters filters={filters} onChange={handleFiltersChange} />

        {isPending ? (
          <LoadingState message="Loading quarantined files…" />
        ) : isError ? (
          isNetworkError(error) ? (
            <BackendUnavailable onRetry={refetch} />
          ) : (
            <div className="rounded-lg border border-[var(--rust)] bg-[var(--rust-tint)] p-[26px] text-[13.5px] text-[var(--rust)] shadow-[var(--ledger-shadow)]">
              {error instanceof Error ? error.message : "Could not load quarantined files."}
            </div>
          )
        ) : (
          <>
            <QuarantineTable rows={data?.content ?? []} onView={handleView} />
            <TablePagination
              currentPage={page}
              totalPages={data?.totalPages ?? 1}
              onPageChange={setPage}
              pageSize={pageSize}
              onPageSizeChange={handlePageSizeChange}
            />
          </>
        )}
      </PermissionGate>
    </DashboardShell>
  )
}
