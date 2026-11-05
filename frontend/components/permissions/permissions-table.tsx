"use client"

import { Badge } from "@/components/ui/badge"
import { SortableTh } from "@/components/ui/sortable-th"
import { useSortableTable } from "@/hooks/use-sortable-table"

import type { PermissionRecord } from "./types"

type SortKey = "name" | "moduleCode" | "actionCode"

interface PermissionsTableProps {
  permissions: PermissionRecord[]
}

/**
 * Name / Description / Module / Action — the Permission List table.
 *
 * Read-only: permissions are a fixed, platform-managed set (each one maps
 * 1:1 to an `@PreAuthorize` check in the backend — see
 * `PermissionController`, which restricts create/update/delete to
 * PLATFORM_ADMIN). Tenant admins can browse and search this catalog to
 * decide what to grant a role, but there's nothing here for them to
 * create, edit, or delete, so there's no Actions column, no view/edit/
 * delete affordances per row, and no raw `permission_code` or
 * shared/custom "Scope" exposed — just what a permission is called, what
 * it does, and where it sits in the Module/Action taxonomy.
 */
export function PermissionsTable({ permissions }: PermissionsTableProps) {
  const { sorted, sortKey, direction, toggleSort } = useSortableTable<
    PermissionRecord,
    SortKey
  >(permissions, {
    name: (p) => p.name,
    moduleCode: (p) => p.moduleCode,
    actionCode: (p) => p.actionCode,
  })

  return (
    <div className="overflow-hidden rounded-lg border border-border bg-card shadow-[var(--ledger-shadow)]">
      <div className="overflow-x-auto">
        <table className="tbl-card w-full min-w-[900px] border-collapse">
          <thead>
            <tr className="border-b border-border">
              <SortableTh
                label="Name"
                active={sortKey === "name"}
                direction={direction}
                onClick={() => toggleSort("name")}
                className="px-3.5"
              />
              <th className="px-3.5 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
                Description
              </th>
              <SortableTh
                label="Module"
                active={sortKey === "moduleCode"}
                direction={direction}
                onClick={() => toggleSort("moduleCode")}
                className="px-3.5"
              />
              <SortableTh
                label="Action"
                active={sortKey === "actionCode"}
                direction={direction}
                onClick={() => toggleSort("actionCode")}
                className="px-3.5"
              />
            </tr>
          </thead>
          <tbody>
            {permissions.length === 0 && (
              <tr>
                <td
                  colSpan={4}
                  className="h-[max(160px,calc(100svh-400px))] px-3.5 py-10 text-center text-[13px] text-muted-foreground"
                >
                  No permissions match your search.
                </td>
              </tr>
            )}
            {sorted.map((permission) => (
              <tr
                key={permission.id}
                className="border-b border-border last:border-b-0 hover:bg-muted"
              >
                <td className="px-3.5 py-3 text-[13.5px] font-semibold text-foreground">
                  {permission.name}
                </td>
                <td className="px-3.5 py-3 text-[13px] leading-[1.5] text-muted-foreground">
                  {permission.description || "No description provided."}
                </td>
                <td className="px-3.5 py-3">
                  <Badge variant="outline" className="font-mono text-[11px]">
                    {permission.moduleName}
                  </Badge>
                </td>
                <td className="px-3.5 py-3">
                  <Badge variant="outline" className="font-mono text-[11px]">
                    {permission.actionName}
                  </Badge>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
