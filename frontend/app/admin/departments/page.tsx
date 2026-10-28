"use client"

import { useMemo, useState } from "react"
import Link from "next/link"
import { useRouter } from "next/navigation"
import { Plus } from "lucide-react"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { DepartmentsTable } from "@/components/departments/departments-table"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { BackendUnavailable } from "@/components/ui/backend-unavailable"
import { LoadingState } from "@/components/ui/loading-state"
import { SearchInput } from "@/components/ui/search-input"
import { SearchableSelect } from "@/components/ui/searchable-select"
import { TablePagination } from "@/components/ui/table-pagination"
import { isNetworkError } from "@/lib/api-error"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { useDepartments } from "@/lib/departments/use-departments"
import { usePageTitle } from "@/lib/use-page-title"
import { DEPARTMENT_ANY_PERMISSION } from "@/lib/workflow/permissions"

import type { DepartmentFilters, DepartmentStatus } from "@/lib/departments/api"

const DEFAULT_PAGE_SIZE = 25

const STATUS_OPTIONS = [
  { value: "active", label: "Active" },
  { value: "inactive", label: "Inactive" },
]

/** Departments - list with server-side search/status filter. Rows open the edit page (details + members). */
export default function DepartmentsPage() {
  usePageTitle("Departments")

  const router = useRouter()
  const { hasPermission, hasAnyPermission } = usePermissions()
  const canView = hasAnyPermission(...DEPARTMENT_ANY_PERMISSION)
  const canCreate = hasPermission(PermissionCodes.OTUA_DEPARTMENTS_CREATE)

  const [search, setSearch] = useState("")
  const [status, setStatus] = useState<"" | DepartmentStatus>("")
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE)

  const filters: DepartmentFilters = useMemo(
    () => ({ search: search.trim() || undefined, status: status || undefined }),
    [search, status]
  )
  const { data, isPending, isError, error, refetch } = useDepartments(filters, page, pageSize, canView)

  return (
    <DashboardShell initialActiveLabel="Departments">
      <AdminBreadcrumb className="mb-3" segments={[{ label: "Dashboard", href: "/" }, { label: "Departments" }]} />
      <div className="mb-[22px] max-w-[680px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">Departments</h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Your organization&apos;s departments. Each user belongs to at most one, and a workflow stage can route
          approval to a department&apos;s members as a pool.
        </p>
      </div>

      <PermissionGate permission={DEPARTMENT_ANY_PERMISSION}>
        <div className="mb-4 flex flex-wrap items-center gap-2.5">
          <SearchInput
            value={search}
            onChange={(value) => { setSearch(value); setPage(1) }}
            placeholder="Search name or code…"
            className="w-[260px]"
            aria-label="Search departments"
          />
          <SearchableSelect
            allLabel="All statuses"
            searchable={false}
            selected={status}
            onChange={(value) => { setStatus(value as "" | DepartmentStatus); setPage(1) }}
            options={STATUS_OPTIONS}
            className="w-[160px]"
          />
          {canCreate && (
            <Link
              href="/admin/departments/new"
              className="ml-auto flex items-center gap-1.5 rounded-btn border border-primary bg-primary px-3.5 py-2.5 text-[13px] font-semibold text-primary-foreground"
            >
              <Plus size={16} />
              Add Department
            </Link>
          )}
        </div>

        {isPending ? (
          <LoadingState message="Loading departments…" />
        ) : isError ? (
          isNetworkError(error) ? (
            <BackendUnavailable onRetry={refetch} />
          ) : (
            <div className="rounded-lg border border-[var(--rust)] bg-[var(--rust-tint)] p-[26px] text-[13.5px] text-[var(--rust)] shadow-[var(--ledger-shadow)]">
              {error instanceof Error ? error.message : "Could not load departments."}
            </div>
          )
        ) : (
          <>
            <DepartmentsTable rows={data?.content ?? []} onView={(row) => router.push(`/admin/departments/${row.departmentId}/edit`)} />
            <TablePagination
              currentPage={page}
              totalPages={data?.totalPages ?? 1}
              onPageChange={setPage}
              pageSize={pageSize}
              onPageSizeChange={(size) => { setPageSize(size); setPage(1) }}
            />
          </>
        )}
      </PermissionGate>
    </DashboardShell>
  )
}
