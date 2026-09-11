"use client"

import { useEffect, useMemo, useState } from "react"
import { AlertTriangle, Loader2, X } from "lucide-react"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { PermissionsTable } from "@/components/permissions/permissions-table"
import { SearchInput } from "@/components/ui/search-input"
import { TablePagination } from "@/components/ui/table-pagination"
import { CheckboxFilter } from "@/components/ui/checkbox-filter"
import { usePermissions } from "@/lib/auth/use-permissions"
import { usePageTitle } from "@/lib/use-page-title"
import { usePermissionsStore } from "@/stores/permissions-store"

const DEFAULT_PAGE_SIZE = 10

/**
 * Permission Catalog — Permission List. The platform-wide catalog of
 * grantable permissions (name, description, and the module/action it
 * belongs to). Read-only here: permissions are a fixed, platform-managed
 * set that maps 1:1 to `@PreAuthorize` checks in the backend (see
 * `PermissionController`, which restricts create/update/delete to
 * PLATFORM_ADMIN) — there's no Add/Edit/Delete on this screen, only
 * browsing. Every tenant's roles draw from this same list via Role
 * Permission Configuration (see `stores/permissions-store.ts` for how
 * this relates to that screen).
 */
export default function PermissionListPage() {
  usePageTitle("Permission Catalog")

  const permissions = usePermissionsStore((state) => state.permissions)
  const loading = usePermissionsStore((state) => state.loading)
  const fetchError = usePermissionsStore((state) => state.error)
  const fetchPermissions = usePermissionsStore((state) => state.fetchPermissions)
  const { hasAnyPermission } = usePermissions()
  const canView = hasAnyPermission("role.view", "role.manage")

  const [query, setQuery] = useState("")
  // Empty array = "all selected" / no filter applied
  const [moduleFilters, setModuleFilters] = useState<string[]>([])
  const [actionFilters, setActionFilters] = useState<string[]>([])
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE)

  useEffect(() => {
    // GET /api/v1/permissions requires role.view or role.manage — skip
    // the call entirely for a caller with neither, rather than let it
    // 403 and, since nothing here ever marks that permanent, keep
    // re-firing it on every remount for the rest of the session.
    if (canView) fetchPermissions()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [canView])

  const moduleOptions = useMemo(
    () => Array.from(new Set(permissions.map((p) => p.moduleCode))),
    [permissions]
  )

  // Actions scoped to the currently selected module(s) — when no module is
  // selected (the "All modules" default), this is every distinct action in
  // the whole catalog, same as before; when one or more modules are
  // selected, it narrows to only the actions that actually occur under
  // those modules, so the Action dropdown can never offer a Module+Action
  // combination that doesn't exist (previously independent, which let a
  // user pick e.g. "Roles" + "Approve Document Disposal" and land on an
  // unexplained empty table).
  const actionOptionsForModuleScope = useMemo(() => {
    const scoped =
      moduleFilters.length === 0
        ? permissions
        : permissions.filter((p) => moduleFilters.includes(p.moduleCode))
    return Array.from(new Set(scoped.map((p) => p.actionCode)))
  }, [permissions, moduleFilters])

  // If the Module filter changes to a set that no longer contains one of
  // the currently selected actions, that action selection is now invalid —
  // clear it back to "All actions" rather than silently keeping a stale
  // filter the user can no longer see or account for. Uses the functional
  // form of setActionFilters so this doesn't need actionFilters itself as
  // a dependency (which would otherwise also fire this effect on every
  // action selection, not just module changes).
  useEffect(() => {
    setActionFilters((current) => {
      if (current.length === 0) return current
      const stillValid = current.every((code) => actionOptionsForModuleScope.includes(code))
      return stillValid ? current : []
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [actionOptionsForModuleScope])

  // Friendly display name for a module/action code, e.g. "ASM" -> "Access
  // and Security Management" — built from whatever's already on each
  // PermissionRecord (moduleName/actionName), so the filter dropdowns show
  // the same human-readable labels as the table's Module/Action badges,
  // while `moduleFilters`/`actionFilters` above keep filtering by the raw
  // code. First match wins if a code were ever to resolve to more than one
  // name (in practice it never does — see V5/V6's seed migrations).
  const moduleNameByCode = useMemo(() => {
    const map = new Map<string, string>()
    for (const p of permissions) {
      if (!map.has(p.moduleCode)) map.set(p.moduleCode, p.moduleName)
    }
    return map
  }, [permissions])
  const actionNameByCode = useMemo(() => {
    const map = new Map<string, string>()
    for (const p of permissions) {
      if (!map.has(p.actionCode)) map.set(p.actionCode, p.actionName)
    }
    return map
  }, [permissions])

  const sortedModuleOptions = useMemo(
    () =>
      [...moduleOptions].sort((a, b) =>
        (moduleNameByCode.get(a) ?? a).localeCompare(moduleNameByCode.get(b) ?? b)
      ),
    [moduleOptions, moduleNameByCode]
  )
  const sortedActionOptions = useMemo(
    () =>
      [...actionOptionsForModuleScope].sort((a, b) =>
        (actionNameByCode.get(a) ?? a).localeCompare(actionNameByCode.get(b) ?? b)
      ),
    [actionOptionsForModuleScope, actionNameByCode]
  )

  const filteredPermissions = useMemo(() => {
    const q = query.trim().toLowerCase()
    return permissions.filter((p) => {
      const matchesQuery =
        !q ||
        p.name.toLowerCase().includes(q) ||
        p.description.toLowerCase().includes(q) ||
        p.moduleName.toLowerCase().includes(q) ||
        p.moduleCode.toLowerCase().includes(q) ||
        p.actionName.toLowerCase().includes(q) ||
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
          The platform-wide catalog of grantable permissions, each mapped
          to a module and action. These are managed by the platform team;
          when building a role, you&apos;ll choose which of these
          permissions to grant it.
        </p>
      </div>

      <PermissionGate permission={["role.view", "role.manage"]}>
      <div className="mb-4 flex flex-wrap items-center gap-2.5">
        <SearchInput
          className="min-w-[220px] flex-1"
            value={query}
          onChange={(value) => handleFilterChange(() => setQuery(value))}
            placeholder="Search permissions…"
          />

        <CheckboxFilter
          label="Module"
          options={sortedModuleOptions}
          selected={moduleFilters}
          onChange={(next) => handleFilterChange(() => setModuleFilters(next))}
          getLabel={(code) => moduleNameByCode.get(code) ?? code}
        />

        <CheckboxFilter
          label="Action"
          options={sortedActionOptions}
          selected={actionFilters}
          onChange={(next) => handleFilterChange(() => setActionFilters(next))}
          getLabel={(code) => actionNameByCode.get(code) ?? code}
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
      </div>

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
          <PermissionsTable permissions={pagePermissions} />
          <TablePagination
            currentPage={currentPage}
            totalPages={totalPages}
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
