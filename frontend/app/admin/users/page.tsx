"use client"

import { useMemo, useState } from "react"
import Link from "next/link"
import { useRouter } from "next/navigation"
import { ChevronDown, Plus, Search } from "lucide-react"

import { DashboardShell } from "@/components/layout/dashboard-shell"
import { initialRoles } from "@/components/roles/data"
import { UsersTable } from "@/components/users/users-table"
import type { UserRecord } from "@/components/users/types"
import { useUsersStore } from "@/stores/users-store"

const ROLE_OPTIONS = ["all", ...initialRoles.map((role) => role.name)]
const PAGE_SIZE = 5

/**
 * User Management — User List, per dms-admin-users.html. Search/filter,
 * add a user (routes to the admin-invite flow at /admin/users/new), view
 * a user's full detail (routes to /admin/users/[userId], per
 * dms-view-user.html), or edit/activate/deactivate an existing account.
 *
 * "Edit" is still wired to a callback rather than a route — no mockup for
 * that screen exists yet, so I didn't invent it. Swap handleEdit for real
 * navigation once it does.
 */
export default function UserListPage() {
  const router = useRouter()
  const users = useUsersStore((state) => state.users)
  const toggleStatus = useUsersStore((state) => state.toggleStatus)

  const [query, setQuery] = useState("")
  const [roleFilter, setRoleFilter] = useState("all")
  const [statusFilter, setStatusFilter] = useState<"all" | "active" | "inactive">("all")
  const [page, setPage] = useState(1)

  const filteredUsers = useMemo(() => {
    const q = query.trim().toLowerCase()
    return users.filter((user) => {
      const matchesQuery =
        !q ||
        user.name.toLowerCase().includes(q) ||
        user.username.toLowerCase().includes(q) ||
        user.email.toLowerCase().includes(q)
      const matchesRole = roleFilter === "all" || user.roles.includes(roleFilter)
      const matchesStatus = statusFilter === "all" || user.status === statusFilter
      return matchesQuery && matchesRole && matchesStatus
    })
  }, [users, query, roleFilter, statusFilter])

  const totalPages = Math.max(1, Math.ceil(filteredUsers.length / PAGE_SIZE))
  const currentPage = Math.min(page, totalPages)
  const pageUsers = filteredUsers.slice((currentPage - 1) * PAGE_SIZE, currentPage * PAGE_SIZE)

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
    console.log("Edit user", user.id)
  }

  return (
    <DashboardShell initialActiveLabel="Users & Roles">
      <div className="mb-[22px] max-w-[680px]">
          <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">
            User Management — User List
          </h1>
          <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
            Users within the current tenant. Search or filter by role and status, add a user, or view,
            edit, and activate/deactivate existing accounts.
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
            onChange={(event) => handleFilterChange(() => setQuery(event.target.value))}
            placeholder="Search by name, username, or email…"
            className="h-[38px] w-full rounded-[9px] border border-border bg-muted pr-3 pl-9 text-[13.5px] text-foreground outline-none placeholder:text-[var(--ink-faint)] focus-visible:border-primary focus-visible:bg-card"
          />
        </div>

        <div className="relative w-[170px]">
          <select
            value={roleFilter}
            onChange={(event) => handleFilterChange(() => setRoleFilter(event.target.value))}
            className="h-[38px] w-full appearance-none rounded-[9px] border border-border bg-muted px-3 text-[13.5px] text-foreground outline-none focus-visible:border-primary focus-visible:bg-card"
          >
            {ROLE_OPTIONS.map((role) => (
              <option key={role} value={role}>
                {role === "all" ? "All roles" : role}
              </option>
            ))}
          </select>
          <ChevronDown
            size={14}
            className="pointer-events-none absolute top-1/2 right-3 -translate-y-1/2 text-[var(--ink-faint)]"
          />
        </div>

        <div className="relative w-[150px]">
          <select
            value={statusFilter}
            onChange={(event) =>
              handleFilterChange(() => setStatusFilter(event.target.value as "all" | "active" | "inactive"))
            }
            className="h-[38px] w-full appearance-none rounded-[9px] border border-border bg-muted px-3 text-[13.5px] text-foreground outline-none focus-visible:border-primary focus-visible:bg-card"
          >
            <option value="all">All statuses</option>
            <option value="active">Active</option>
            <option value="inactive">Inactive</option>
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

      <UsersTable users={pageUsers} onToggleStatus={toggleStatus} onView={handleView} onEdit={handleEdit} />

      {totalPages > 1 && (
        <div className="mt-3.5 flex items-center justify-end gap-1.5">
          {Array.from({ length: totalPages }, (_, i) => i + 1).map((n) => (
            <button
              key={n}
              type="button"
              onClick={() => setPage(n)}
              className={
                "flex size-7 items-center justify-center rounded-[6px] border text-[12.5px] " +
                (n === currentPage
                  ? "border-primary bg-primary-tint font-semibold text-primary"
                  : "border-border bg-card text-muted-foreground")
              }
            >
              {n}
            </button>
          ))}
        </div>
      )}
    </DashboardShell>
  )
}