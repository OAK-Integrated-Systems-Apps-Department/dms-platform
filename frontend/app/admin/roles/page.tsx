"use client"

import { useEffect, useMemo, useState } from "react"
import Link from "next/link"
import { AlertTriangle, Grid3x3, Loader2, Plus, Search } from "lucide-react"
import { toast } from "sonner"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { RolesTable } from "@/components/roles/roles-table"
import { TablePagination } from "@/components/ui/table-pagination"
import { usePageTitle } from "@/lib/use-page-title"
import { useUsers } from "@/lib/users/use-users"
import { useRolesStore } from "@/stores/roles-store"

const DEFAULT_PAGE_SIZE = 10

/**
 * Role Management — Role List (R1). Search roles, add a role, or
 * edit/activate/deactivate/delete an existing one. System roles are
 * read-only — see the footnote and `RolesTable`'s handling of
 * `role.system`. (KNOWN GAP: the backend has no "system role" concept
 * yet — see the note on `fromBackend` in `stores/roles-store.ts` — so
 * every role this fetches from the API comes back editable/deletable
 * regardless of that footnote.)
 */
export default function RoleListPage() {
  usePageTitle("Role List")

  const roles = useRolesStore((state) => state.roles)
  const loading = useRolesStore((state) => state.loading)
  const fetchError = useRolesStore((state) => state.error)
  const fetchRoles = useRolesStore((state) => state.fetchRoles)
  const toggleStatus = useRolesStore((state) => state.toggleStatus)
  const deleteRole = useRolesStore((state) => state.deleteRole)

  // FIXED KNOWN GAP: `RoleRecord.assignedUsers` from the store is always
  // 0 — there's no `GET /api/v1/roles/{roleId}/users` endpoint, so it was
  // never wired to anything real (see `fromBackend`'s doc comment in
  // `stores/roles-store.ts`). Same fix Role Details already used: pull
  // the real user list (`useUsers()`, the same data source the User List
  // page itself is built on) and count matches on `user.roles.includes`
  // client-side, rather than trusting the store's placeholder field.
  const { users } = useUsers()
  const assignedUserCounts = useMemo(() => {
    const counts = new Map<string, number>()
    for (const user of users) {
      for (const roleName of user.roles) {
        counts.set(roleName, (counts.get(roleName) ?? 0) + 1)
      }
    }
    return counts
  }, [users])
  const rolesWithRealCounts = useMemo(
    () =>
      roles.map((role) => ({
        ...role,
        assignedUsers: assignedUserCounts.get(role.name) ?? 0,
      })),
    [roles, assignedUserCounts]
  )

  const [query, setQuery] = useState("")
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE)
  const [actionError, setActionError] = useState<string | null>(null)

  useEffect(() => {
    fetchRoles()
    // Only ever needs to run once on mount — `fetchRoles` is a stable
    // zustand action reference, not a changing dependency.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const filteredRoles = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return rolesWithRealCounts
    return rolesWithRealCounts.filter(
      (role) =>
        role.name.toLowerCase().includes(q) ||
        role.description.toLowerCase().includes(q)
    )
  }, [rolesWithRealCounts, query])

  const totalPages = Math.max(1, Math.ceil(filteredRoles.length / pageSize))
  const currentPage = Math.min(page, totalPages)
  const pageRoles = filteredRoles.slice(
    (currentPage - 1) * pageSize,
    currentPage * pageSize
  )

  function handleQueryChange(value: string) {
    setQuery(value)
    setPage(1)
  }

  function handlePageSizeChange(size: number) {
    setPageSize(size)
    setPage(1)
  }

  async function handleToggleStatus(id: string) {
    setActionError(null)
    const role = roles.find((r) => r.id === id)
    const { error } = await toggleStatus(id)
    if (error) {
      setActionError(error)
      toast.error(error)
      return
    }
    toast.success(
      role
        ? `${role.name} is now ${role.status === "Active" ? "inactive" : "active"}.`
        : "Role status updated."
    )
  }

  async function handleDeleteRole(id: string) {
    setActionError(null)
    const role = roles.find((r) => r.id === id)
    const { error } = await deleteRole(id)
    if (error) {
      toast.error(error)
    } else {
      toast.success(role ? `${role.name} deleted.` : "Role deleted.")
    }
    return error
  }

  return (
    <DashboardShell initialActiveLabel="Users & Roles">
      <AdminBreadcrumb
        className="mb-3"
        segments={[{ label: "Dashboard", href: "/" }, { label: "Roles" }]}
      />
      <div className="mb-[22px] max-w-[680px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">
          Role Management - Role List
        </h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Roles available within the current tenant. Search, add a role, or
          view, edit, activate/deactivate, or delete existing roles.
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
            onChange={(event) => handleQueryChange(event.target.value)}
            placeholder="Search roles…"
            className="h-[38px] w-full rounded-[9px] border border-border bg-muted pr-3 pl-9 text-[13.5px] text-foreground outline-none placeholder:text-[var(--ink-faint)] focus-visible:border-primary focus-visible:bg-card"
          />
        </div>
        <Link
          href="/admin/roles/matrix"
          className="flex items-center gap-1.5 rounded-[9px] border border-[var(--border-strong)] bg-card px-3.5 py-2.5 text-[13px] font-semibold text-foreground"
        >
          <Grid3x3 size={16} />
          Permission Matrix
        </Link>
        <Link
          href="/admin/roles/new"
          className="flex items-center gap-1.5 rounded-[9px] border border-primary bg-primary px-3.5 py-2.5 text-[13px] font-semibold text-primary-foreground"
        >
          <Plus size={16} />
          Add Role
        </Link>
      </div>

      {actionError && (
        <div className="mb-4 flex items-start gap-2 rounded-[10px] border border-[var(--rust)] bg-[var(--rust-tint)] px-3.5 py-2.5 text-[12.5px] text-[var(--rust)]">
          <AlertTriangle size={14} className="mt-0.5 shrink-0" />
          <span>{actionError}</span>
        </div>
      )}

      {loading && roles.length === 0 ? (
        <div className="flex items-center justify-center gap-2 rounded-[14px] border border-border bg-card py-16 text-[13.5px] text-muted-foreground shadow-[var(--ledger-shadow)]">
          <Loader2 size={16} className="animate-spin" />
          Loading roles…
        </div>
      ) : fetchError && roles.length === 0 ? (
        <div className="flex flex-col items-center gap-3 rounded-[14px] border border-border bg-card py-16 text-center shadow-[var(--ledger-shadow)]">
          <AlertTriangle size={20} className="text-[var(--rust)]" />
          <p className="text-[13.5px] text-muted-foreground">{fetchError}</p>
          <button
            type="button"
            onClick={() => fetchRoles()}
            className="rounded-[9px] border border-[var(--border-strong)] bg-card px-3.5 py-2 text-[12.5px] font-semibold text-foreground"
          >
            Try again
          </button>
        </div>
      ) : (
        <>
          <RolesTable
            roles={pageRoles}
            onToggleStatus={handleToggleStatus}
            onDeleteRole={handleDeleteRole}
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