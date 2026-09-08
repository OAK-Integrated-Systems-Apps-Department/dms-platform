"use client"

import { useState } from "react"
import Link from "next/link"
import { Eye, Pencil, Trash2 } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { SortableTh } from "@/components/ui/sortable-th"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { useSortableTable } from "@/hooks/use-sortable-table"
import { getTooltip } from "@/lib/tooltips"

import { PermissionDeleteDialog } from "./permission-delete-dialog"
import type { PermissionRecord } from "./types"

type SortKey = "code" | "name" | "moduleCode" | "actionCode"

interface PermissionsTableProps {
  permissions: PermissionRecord[]
  /** Resolves with an error message on failure, or null on success — passed straight through to `PermissionDeleteDialog`. */
  onDeletePermission: (id: string) => Promise<string | null>
}

/** Code / Name / Module / Action / Actions — the Permission List table. */
export function PermissionsTable({ permissions, onDeletePermission }: PermissionsTableProps) {
  const { sorted, sortKey, direction, toggleSort } = useSortableTable<
    PermissionRecord,
    SortKey
  >(permissions, {
    code: (p) => p.code,
    name: (p) => p.name,
    moduleCode: (p) => p.moduleCode,
    actionCode: (p) => p.actionCode,
  })

  const [pendingDeletePermission, setPendingDeletePermission] =
    useState<PermissionRecord | null>(null)

  return (
    <div className="overflow-hidden rounded-[14px] border border-border bg-card shadow-[var(--ledger-shadow)]">
      <div className="overflow-x-auto">
        <table className="w-full min-w-[820px] border-collapse">
          <thead>
            <tr className="border-b border-border">
              <SortableTh
                label="Code"
                active={sortKey === "code"}
                direction={direction}
                onClick={() => toggleSort("code")}
                className="px-3.5"
              />
              <SortableTh
                label="Name"
                active={sortKey === "name"}
                direction={direction}
                onClick={() => toggleSort("name")}
                className="px-3.5"
              />
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
              <th className="px-3.5 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
                Actions
              </th>
            </tr>
          </thead>
          <tbody>
            {permissions.length === 0 && (
              <tr>
                <td
                  colSpan={5}
                  className="px-3.5 py-10 text-center text-[13px] text-muted-foreground"
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
                <td className="px-3.5 py-3 font-mono text-[12.5px] font-semibold text-primary">
                    {permission.code}
                </td>
                <td className="px-3.5 py-3 text-[13.5px] text-foreground">
                  {permission.name}
                </td>
                <td className="px-3.5 py-3">
                  <Badge variant="outline" className="font-mono text-[11px]">
                    {permission.moduleCode}
                  </Badge>
                </td>
                <td className="px-3.5 py-3">
                  <Badge variant="outline" className="font-mono text-[11px]">
                    {permission.actionCode}
                  </Badge>
                </td>
                <td className="px-3.5 py-3">
                  <div className="flex items-center gap-1">
                    <Tooltip>
                      <TooltipTrigger
                        render={(props) => (
                          <Link
                            {...props}
                            href={`/admin/permissions/${permission.id}`}
                            aria-label={`View ${permission.name}`}
                            className="flex size-7 items-center justify-center rounded-[7px] text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                          >
                            <Eye size={15} />
                          </Link>
                        )}
                      />
                      <TooltipContent>{getTooltip("permissions.actions.view")}</TooltipContent>
                    </Tooltip>

                    <Tooltip>
                      <TooltipTrigger
                        render={(props) => (
                          <Link
                            {...props}
                            href={`/admin/permissions/${permission.id}/edit`}
                            aria-label={`Edit ${permission.name}`}
                            className="flex size-7 items-center justify-center rounded-[7px] text-primary transition-colors hover:bg-muted"
                          >
                            <Pencil size={15} />
                          </Link>
                        )}
                      />
                      <TooltipContent>{getTooltip("permissions.actions.edit")}</TooltipContent>
                    </Tooltip>

                    <Tooltip>
                      <TooltipTrigger
                        render={(props) => (
                          <button
                            {...props}
                            type="button"
                            onClick={() => setPendingDeletePermission(permission)}
                            aria-label={`Delete ${permission.name}`}
                            className="flex size-7 items-center justify-center rounded-[7px] text-[var(--rust)] transition-colors hover:bg-[var(--rust-tint)]"
                          >
                            <Trash2 size={15} />
                          </button>
                        )}
                      />
                      <TooltipContent>{getTooltip("permissions.actions.delete")}</TooltipContent>
                    </Tooltip>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {pendingDeletePermission && (
        <PermissionDeleteDialog
          permission={pendingDeletePermission}
          open={pendingDeletePermission !== null}
          onOpenChange={(open) => {
            if (!open) setPendingDeletePermission(null)
          }}
          onConfirm={() => onDeletePermission(pendingDeletePermission.id)}
        />
      )}
    </div>
  )
}