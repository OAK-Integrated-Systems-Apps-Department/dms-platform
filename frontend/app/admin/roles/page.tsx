"use client"

import { useMemo, useState } from "react"
import Link from "next/link"
import { Plus, Search } from "lucide-react"

import { DashboardShell } from "@/components/layout/dashboard-shell"
import { RolesTable } from "@/components/roles/roles-table"
import { useRolesStore } from "@/stores/roles-store"

/**
 * Role Management — Role List (R1). Search roles, add a role, or
 * edit/activate/deactivate an existing one. System roles are read-only —
 * see the footnote and `RolesTable`'s handling of `role.system`.
 */
export default function RoleListPage() {
  const roles = useRolesStore((state) => state.roles)
  const toggleStatus = useRolesStore((state) => state.toggleStatus)
  const [query, setQuery] = useState("")

  const filteredRoles = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return roles
    return roles.filter(
      (role) =>
        role.name.toLowerCase().includes(q) ||
        role.description.toLowerCase().includes(q)
    )
  }, [roles, query])

  return (
    <DashboardShell initialActiveLabel="Users & Roles">
      <div className="mb-[22px] max-w-[680px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">
          Role Management — Role List
        </h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Roles available within the current tenant. Search, add a role, or
          view, edit, and activate/deactivate existing roles.
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
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Search roles…"
            className="h-[38px] w-full rounded-[9px] border border-border bg-muted pr-3 pl-9 text-[13.5px] text-foreground outline-none placeholder:text-[var(--ink-faint)] focus-visible:border-primary focus-visible:bg-card"
          />
        </div>
        <Link
          href="/admin/roles/new"
          className="flex items-center gap-1.5 rounded-[9px] border border-primary bg-primary px-3.5 py-2.5 text-[13px] font-semibold text-primary-foreground"
        >
          <Plus size={16} />
          Add Role
        </Link>
      </div>

      <RolesTable roles={filteredRoles} onToggleStatus={toggleStatus} />

      <p className="mt-3.5 text-[12px] text-muted-foreground">
        Add, edit, and activate/deactivate controls are only shown for custom
        roles — system roles are read-only.
      </p>
    </DashboardShell>
  )
}
