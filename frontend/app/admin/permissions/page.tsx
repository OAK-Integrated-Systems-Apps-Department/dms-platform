"use client"

import { useEffect, useMemo, useState } from "react"
import Link from "next/link"
import { AlertTriangle, Loader2, Plus, Search, X } from "lucide-react"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { PermissionsTable } from "@/components/permissions/permissions-table"
import { TablePagination } from "@/components/ui/table-pagination"
import { CheckboxFilter } from "@/components/ui/checkbox-filter"
import { usePageTitle } from "@/lib/use-page-title"
import { usePermissionsStore } from "@/stores/permissions-store"

const DEFAULT_PAGE_SIZE = 10

/**
 * Permission Catalog — Permission List. The platform-wide catalog of
 * grantable permissions (code, name, description, and the module/action
 * it belongs to). Not tenant-scoped — every tenant's roles draw from this
 * same list via Role Permission Configuration (see
 * `stores/permissions-store.ts` for how this relates to that screen).
 */
export default function PermissionListPage() {
  usePageTitle("Permission Catalog")

  const permissions = usePermissionsStore((state) => state.permissions)
  const loading = usePermissionsStore((state) => state.loading)
  const fetchError = usePermissionsStore((state) => state.error)
  const fetchPermissions = usePermissionsStore((state) => state.fetchPermissions)
  const deletePermission = usePermissionsStore((state) => state.deletePermission)

  const [query, setQuery] = useState("")
  // Empty array = "all selected" / no filter applied
  const [moduleFilters, setModuleFilters] = useState<string[]>([])
  const [actionFilters, setActionFilters] = useState<string[]>([])
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE)
  const [actionError, setActionError] = useState<string | null>(null)

  useEffect(() => {
    fetchPermissions()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const moduleOptions = useMemo(
    () => Array.from(new Set(permissions.map((p) => p.moduleCode))).sort(),
    [permissions]
  )
  const actionOptions = useMemo(
    () => Array.from(new Set(permissions.map((p) => p.actionCode))).sort(),
    [permissions]
  )

  const filteredPermissions = useMemo(() => {
    const q = query.trim().toLowerCase()
    return permissions.filter((p) => {
      const matchesQuery =
        !q ||
        p.code.toLowerCase().includes(q) ||
        p.name.toLowerCase().includes(q) ||
        p.description.toLowerCase().includes(q) ||
        p.moduleCode.toLowerCase().includes(q) ||
        p.actionCode.toLowerCase().includes(q)
      const matchesModule =
        moduleFilters.length === 0 || moduleFilters.includes(p.moduleCode)
      const matchesAction =
        actionFilters.length === 0 || actionFilters.includes(p.actionCode)
      return matchesQuery && matchesModule && matchesAction
    })
  }, [permissions, query, moduleFilters, actionFilters])

  const totalPages = Math.max(1, Math.ceil(filteredPermissions.length / pageSize))
  const currentPage = Math.min(page, totalPages)
  const pagePermissions = filteredPermissions.slice(
    (currentPage - 1) * pageSize,
    currentPage * pageSize
  )

  const hasActiveFilters = moduleFilters.length > 0 || actionFilters.length > 0

  function handlePageSizeChange(size: number) {
    setPageSize(size)
    setPage(1)
  }

  function handleFilterChange(update: () => void) {
    update()
    setPage(1)
  }

  function handleClearAllFilters() {
    handleFilterChange(() => {
      setModuleFilters([])
      setActionFilters([])
    })
  }

  async function handleDeletePermission(id: string) {
    setActionError(null)
    const { error } = await deletePermission(id)
    return error
  }

  return (
    <DashboardShell initialActiveLabel="Permissions">
      <AdminBreadcrumb
        className="mb-3"
        segments={[{ label: "Dashboard", href: "/" }, { label: "Permissions" }]}
      />
      <div className="mb-[22px] max-w-[680px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">
          Permission Catalog
        </h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          The platform-wide set of grantable permissions, each mapped to a
          module and action. Roles are built by granting permissions from
          this catalog — add, edit, or retire one here.
        </p>
      </div>

      <div className="mb-4 flex flex-wrap items-center gap-2.5">
        <div className="relative min-w-[220px] flex-1">
          <Search
            size={16}
            className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2 text-[var(--ink-faint)]"
          />
          <input
            type="text"
            value={query}
            onChange={(event) =>
              handleFilterChange(() => setQuery(event.target.value))
            }
            placeholder="Search permissions, modules, or actions…"
            className="h-[38px] w-full rounded-[9px] border border-border bg-muted pr-3 pl-9 text-[13.5px] text-foreground outline-none placeholder:text-[var(--ink-faint)] focus-visible:border-primary focus-visible:bg-card"
          />
        </div>

        <CheckboxFilter
          label="Module"
          options={moduleOptions}
          selected={moduleFilters}
          onChange={(next) => handleFilterChange(() => setModuleFilters(next))}
        />

        <CheckboxFilter
          label="Action"
          options={actionOptions}
          selected={actionFilters}
          onChange={(next) => handleFilterChange(() => setActionFilters(next))}
        />

        {hasActiveFilters && (
          <button
            type="button"
            onClick={handleClearAllFilters}
            className="flex h-[38px] items-center gap-1.5 rounded-[9px] border border-border bg-muted px-3 text-[13.5px] font-semibold text-muted-foreground hover:border-[var(--border-strong)] hover:bg-card hover:text-foreground"
          >
            <X size={14} />
            Clear filters
          </button>
        )}

        <Link
          href="/admin/permissions/new"
          className="ml-auto flex items-center gap-1.5 rounded-[9px] border border-primary bg-primary px-3.5 py-2.5 text-[13px] font-semibold text-primary-foreground"
        >
          <Plus size={16} />
          Add Permission
        </Link>
      </div>

      {actionError && (
        <div className="mb-4 flex items-start gap-2 rounded-[10px] border border-[var(--rust)] bg-[var(--rust-tint)] px-3.5 py-2.5 text-[12.5px] text-[var(--rust)]">
          <AlertTriangle size={14} className="mt-0.5 shrink-0" />
          <span>{actionError}</span>
        </div>
      )}

      {loading && permissions.length === 0 ? (
        <div className="flex items-center justify-center gap-2 rounded-[14px] border border-border bg-card py-16 text-[13.5px] text-muted-foreground shadow-[var(--ledger-shadow)]">
          <Loader2 size={16} className="animate-spin" />
          Loading permissions…
        </div>
      ) : fetchError && permissions.length === 0 ? (
        <div className="flex flex-col items-center gap-3 rounded-[14px] border border-border bg-card py-16 text-center shadow-[var(--ledger-shadow)]">
          <AlertTriangle size={20} className="text-[var(--rust)]" />
          <p className="text-[13.5px] text-muted-foreground">{fetchError}</p>
          <button
            type="button"
            onClick={() => fetchPermissions()}
            className="rounded-[9px] border border-[var(--border-strong)] bg-card px-3.5 py-2 text-[12.5px] font-semibold text-foreground"
          >
            Try again
          </button>
        </div>
      ) : (
        <>
          <PermissionsTable
            permissions={pagePermissions}
            onDeletePermission={handleDeletePermission}
          />
          <TablePagination
            currentPage={currentPage}
            totalPages={totalPages}
            onPageChange={setPage}
            pageSize={pageSize}
            onPageSizeChange={handlePageSizeChange}
          />
        </>
      )}
    </DashboardShell>
  )
}