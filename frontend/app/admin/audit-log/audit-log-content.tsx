"use client"

import { LoadFailed } from "@/components/ui/load-failed"
import { useMemo, useState } from "react"
import { useRouter, useSearchParams } from "next/navigation"
import { toast } from "sonner"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import {
  AuditLogFilters,
  EMPTY_AUDIT_LOG_FILTERS,
  type AuditLogFilterState,
} from "@/components/audit-log/audit-log-filters"
import {
  AuditLogTable,
  type AuditLogSortField,
} from "@/components/audit-log/audit-log-table"
import { BackendUnavailable } from "@/components/ui/backend-unavailable"
import { LoadingState } from "@/components/ui/loading-state"
import { TablePagination } from "@/components/ui/table-pagination"
import { isNetworkError } from "@/lib/api-error"
import { exportAuditEventsCsv } from "@/lib/audit-events/api"
import type {
  AuditEventResponseDto,
  AuditEventSearchFilters,
} from "@/lib/audit-events/api"
import { useActorOptions } from "@/lib/audit-events/use-actor-options"
import { useAuditEvents } from "@/lib/audit-events/use-audit-events"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { usePageTitle } from "@/lib/use-page-title"

const DEFAULT_PAGE_SIZE = 25

/**
 * Audit Log — the platform's immutable audit trail, backed by
 * `GET /api/v1/audit-events` (real server-side filtering/pagination,
 * unlike Users/Roles - see `useAuditEvents`'s own doc comment). Row click
 * opens the detail page (`/admin/audit-log/[id]`), where the full
 * before/after diff lives.
 *
 * Split out of page.tsx (which just wraps this in Suspense) because
 * `useSearchParams()` below requires one - same pattern as
 * login-page-content.tsx and role-permissions-content.tsx.
 */
export function AuditLogContent() {
  usePageTitle("Audit Log")

  const router = useRouter()
  const searchParams = useSearchParams()
  const { hasPermission, hasAnyPermission } = usePermissions()

  const canFilterByActor = hasAnyPermission(
    PermissionCodes.OTUA_USERS_VIEW,
    PermissionCodes.OTUA_USERS_UPDATE,
    PermissionCodes.OTUA_USERS_RESET_PASSWORD,
    PermissionCodes.OTUA_USERS_ACTIVATE,
    PermissionCodes.OTUA_USERS_DEACTIVATE,
    PermissionCodes.OTUA_USERS_DELETE,
    PermissionCodes.OTUA_USERS_ASSIGN_ROLE
  )
  const canExport = hasPermission(PermissionCodes.ABDR_EXPORT_AUDIT_REPORTS)
  const actorOptions = useActorOptions(canFilterByActor)

  const [filters, setFilters] = useState<AuditLogFilterState>(
    EMPTY_AUDIT_LOG_FILTERS
  )
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE)
  const [sortField, setSortField] = useState<AuditLogSortField>("occurredAt")
  const [sortDirection, setSortDirection] = useState<"asc" | "desc">("desc")
  const [isExporting, setIsExporting] = useState(false)

  // The Audit Event detail page's "Part of operation" link navigates here
  // as `?correlationId=...` (see [id]/page.tsx) - picked up here rather
  // than read once via useState's lazy initializer so a second link
  // clicked while already on this page (a different correlationId) is
  // also picked up, not just the very first page load. Adjusted during
  // render (comparing against the last-seen searchParams) rather than in an
  // effect; starts as null so the initial page load is handled too.
  const [seenSearchParams, setSeenSearchParams] = useState<
    typeof searchParams | null
  >(null)
  if (searchParams !== seenSearchParams) {
    setSeenSearchParams(searchParams)
    const correlationId = searchParams.get("correlationId")
    if (correlationId) {
      setFilters((current) => ({ ...current, correlationId }))
      setPage(1)
    }
    // `?module=DE` - e.g. Encryption Keys' "View key activity" link.
    const moduleCode = searchParams.get("module")
    if (moduleCode) {
      setFilters((current) => ({ ...current, modules: [moduleCode] }))
      setPage(1)
    }
  }

  function handleFiltersChange(update: Partial<AuditLogFilterState>) {
    setFilters((current) => ({ ...current, ...update }))
    setPage(1)
  }

  function handlePageSizeChange(size: number) {
    setPageSize(size)
    setPage(1)
  }

  function handleSortChange(field: AuditLogSortField) {
    if (field === sortField) {
      setSortDirection((direction) => (direction === "asc" ? "desc" : "asc"))
    } else {
      setSortField(field)
      setSortDirection("desc")
    }
    setPage(1)
  }

  // filters.occurredFrom/occurredTo are raw <input type="datetime-local">
  // values ("YYYY-MM-DDTHH:mm", no seconds/offset - see AuditLogFilterState's
  // own doc comment). The trailing ":00Z" is required, not decorative: the
  // backend binds this param as a Java OffsetDateTime, whose parser rejects
  // a date-time with no zone/offset at all - omitting it 500s every request
  // with either filter set (a real bug hit testing this in the browser).
  // Treated as literal UTC, not the viewer's local time - an acceptable
  // simplification given nothing else in this app is timezone-aware either
  // (see formatDateTime).
  const apiFilters: AuditEventSearchFilters = useMemo(
    () => ({
      modules: filters.modules.length > 0 ? filters.modules : undefined,
      actions: filters.actions.length > 0 ? filters.actions : undefined,
      actorIds: filters.actorIds.length > 0 ? filters.actorIds : undefined,
      objectTypes:
        filters.objectTypes.length > 0 ? filters.objectTypes : undefined,
      objectId: filters.objectId.trim() || undefined,
      outcome: filters.outcome || undefined,
      severity: filters.severity || undefined,
      occurredFrom: filters.occurredFrom
        ? `${filters.occurredFrom}:00Z`
        : undefined,
      occurredTo: filters.occurredTo ? `${filters.occurredTo}:00Z` : undefined,
      correlationId: filters.correlationId || undefined,
    }),
    [filters]
  )

  const sort = `${sortField},${sortDirection}`
  const { data, isPending, isError, error, refetch } = useAuditEvents(
    apiFilters,
    page,
    pageSize,
    sort
  )

  async function handleExport() {
    setIsExporting(true)
    try {
      await exportAuditEventsCsv(apiFilters)
      toast.success("Audit log exported.")
    } catch (err) {
      toast.error(
        err instanceof Error ? err.message : "Could not export the audit trail."
      )
    } finally {
      setIsExporting(false)
    }
  }

  function handleView(row: AuditEventResponseDto) {
    router.push(`/admin/audit-log/${row.id}`)
  }

  return (
    <DashboardShell initialActiveLabel="Audit Log">
      <AdminBreadcrumb
        className="mb-3"
        segments={[{ label: "Dashboard", href: "/" }, { label: "Audit Log" }]}
      />
      <div className="mb-[22px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">
          Audit Log
        </h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Immutable record of user and system actions within your tenant. Search
          or filter by module, actor, action, object, outcome, severity, or date
          range, and export the filtered results as CSV.
        </p>
      </div>

      <PermissionGate permission={PermissionCodes.ABDR_VIEW_AUDIT_HISTORY}>
        <AuditLogFilters
          filters={filters}
          onChange={handleFiltersChange}
          actorOptions={actorOptions}
          canFilterByActor={canFilterByActor}
          canExport={canExport}
          isExporting={isExporting}
          onExport={handleExport}
        />

        {isPending ? (
          <LoadingState message="Loading audit events…" />
        ) : isError ? (
          isNetworkError(error) ? (
            <BackendUnavailable onRetry={refetch} />
          ) : (
            <LoadFailed error={error} fallback="Could not load audit events." />
          )
        ) : (
          <>
            <AuditLogTable
              rows={data?.content ?? []}
              onView={handleView}
              sortField={sortField}
              sortDirection={sortDirection}
              onSortChange={handleSortChange}
            />

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
