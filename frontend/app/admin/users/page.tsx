"use client"

import { useEffect, useMemo, useState } from "react"
import Link from "next/link"
import { useRouter } from "next/navigation"
import { ChevronDown, Plus, Search } from "lucide-react"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { usePageTitle } from "@/lib/use-page-title"
import { TablePagination } from "@/components/ui/table-pagination"
import { RoleFilterSelect } from "@/components/users/role-filter-select"
import { UsersTable } from "@/components/users/users-table"
import type { UserRecord } from "@/components/users/types"
import { useIsTenantAdmin } from "@/lib/auth/use-is-tenant-admin"
import { useUsers } from "@/lib/users/use-users"
import { useRolesStore } from "@/stores/roles-store"

const DEFAULT_PAGE_SIZE = 10

/**
 * User Management — User List, per dms-admin-users.html. Search/filter,
 * add a user (routes to /admin/users/new), view a user's full detail
 * (routes to /admin/users/[userId], per dms-view-user.html), edit a
 * user's profile (routes to /admin/users/[userId]/edit, per
 * dms-edit-user.html), activate/deactivate an existing account inline, or
 * trigger a password reset (U7) inline.
 */
export default function UserListPage() {
  usePageTitle("User List")

  const router = useRouter()
  const {
    users,
    isPending,
    isError,
    error,
    toggleStatus,
    deleteUser,
    sendPasswordReset,
  } = useUsers()
  const { isTenantAdmin } = useIsTenantAdmin()

  // Live tenant role list from the DB (same store the Roles list and
  // RoleMultiSelect use) rather than the hardcoded components/roles/data.ts
  // catalog, so this filter always matches roles that actually exist.
  const roles = useRolesStore((state) => state.roles)
  const fetchRoles = useRolesStore((state) => state.fetchRoles)
  useEffect(() => {
    if (roles.length === 0) fetchRoles()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  const ROLE_OPTIONS = useMemo(() => roles.map((role) => role.name), [roles])

  const [query, setQuery] = useState("")
  const [roleFilter, setRoleFilter] = useState<string[]>([])
  const [statusFilter, setStatusFilter] = useState<
    "all" | "Active" | "Inactive"
  >("all")
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE)

  const filteredUsers = useMemo(() => {
    const q = query.trim().toLowerCase()
    return users.filter((user) => {
      const matchesQuery =
        !q ||
        user.name.toLowerCase().includes(q) ||
        user.username.toLowerCase().includes(q) ||
        user.email.toLowerCase().includes(q)
      const matchesRole =
        roleFilter.length === 0 ||
        user.roles.some((role) => roleFilter.includes(role))
      const matchesStatus =
        statusFilter === "all" || user.status === statusFilter
      return matchesQuery && matchesRole && matchesStatus
    })
  }, [users, query, roleFilter, statusFilter])

  const totalPages = Math.max(1, Math.ceil(filteredUsers.length / pageSize))
  const currentPage = Math.min(page, totalPages)
  const pageUsers = filteredUsers.slice(
    (currentPage - 1) * pageSize,
    currentPage * pageSize
  )

  function handlePageSizeChange(size: number) {
    setPageSize(size)
    setPage(1)
  }

  function handleFilterChange(update: () => void) {
    update()
    setPage(1)
  }

  // Edit still has no mockup/screen to navigate to — see the component
  // doc comment above. View now goes to the real page.
  function handleView(user: UserRecord) {
    router.push(`/admin/users/${user.id}`)
  }
  function handleEdit(user: UserRecord) {
    router.push(`/admin/users/${user.id}/edit`)
  }

  return (
    <DashboardShell initialActiveLabel="Users & Roles">
      <AdminBreadcrumb
        className="mb-3"
        segments={[{ label: "Dashboard", href: "/" }, { label: "Users" }]}
      />
      <div className="mb-[22px] max-w-[680px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">
          User Management — User List
        </h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Users within the current tenant. Search or filter by role and status,
          add a user, or view, edit, and activate/deactivate existing accounts.
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
            placeholder="Search by name, username, or email…"
            className="h-[38px] w-full rounded-[9px] border border-border bg-muted pr-3 pl-9 text-[13.5px] text-foreground outline-none placeholder:text-[var(--ink-faint)] focus-visible:border-primary focus-visible:bg-card"
          />
        </div>

        <RoleFilterSelect
          className="w-[170px]"
          options={ROLE_OPTIONS}
          selected={roleFilter}
          onChange={(roles) => handleFilterChange(() => setRoleFilter(roles))}
        />

        <div className="relative w-[150px]">
          <select
            value={statusFilter}
            onChange={(event) =>
              handleFilterChange(() =>
                setStatusFilter(
                  event.target.value as "all" | "Active" | "Inactive"
                )
              )
            }
            className="h-[38px] w-full appearance-none rounded-[9px] border border-border bg-muted px-3 text-[13.5px] text-foreground outline-none focus-visible:border-primary focus-visible:bg-card"
          >
            <option value="all">All statuses</option>
            <option value="Active">Active</option>
            <option value="Inactive">Inactive</option>
          </select>
          <ChevronDown
            size={14}
            className="pointer-events-none absolute top-1/2 right-3 -translate-y-1/2 text-[var(--ink-faint)]"
          />
        </div>

        <Link
          href="/admin/users/new"
          className="flex items-center gap-1.5 rounded-[9px] border border-primary bg-primary px-3.5 py-2.5 text-[13px] font-semibold text-primary-foreground"
        >
          <Plus size={16} />
          Add user
        </Link>
      </div>

      {isPending ? (
        <div className="rounded-[14px] border border-border bg-card p-[26px] text-[13.5px] text-muted-foreground shadow-[var(--ledger-shadow)]">
          Loading users…
        </div>
      ) : isError ? (
        <div className="rounded-[14px] border border-[var(--rust)] bg-[var(--rust-tint)] p-[26px] text-[13.5px] text-[var(--rust)] shadow-[var(--ledger-shadow)]">
          {error instanceof Error ? error.message : "Could not load users."}
        </div>
      ) : (
        <>
      <UsersTable
        users={pageUsers}
        onToggleStatus={toggleStatus}
        onResetPassword={sendPasswordReset}
        onDelete={deleteUser}
        canDelete={isTenantAdmin}
        onView={handleView}
        onEdit={handleEdit}
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