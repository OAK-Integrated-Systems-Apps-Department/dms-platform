"use client"

import { useState } from "react"
import Link from "next/link"
import { Eye, Pencil, ShieldCheck, Trash2, UserCheck, UserX } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { SortableTh } from "@/components/ui/sortable-th"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { useSortableTable } from "@/hooks/use-sortable-table"
import { getTooltip } from "@/lib/tooltips"
import { cn } from "@/lib/utils"

import { RoleDeleteDialog } from "./role-delete-dialog"
import { formatRoleNameForDisplay } from "./role-utils"
import type { RoleRecord } from "./types"

type SortKey = "name" | "description" | "status" | "assignedUsers"

interface RolesTableProps {
  roles: RoleRecord[]
  onToggleStatus: (id: string) => void
  /** Resolves with an error message on failure, or null on success — passed straight through to `RoleDeleteDialog`. */
  onDeleteRole: (id: string) => Promise<string | null>
  /**
   * Whether the signed-in user may edit, activate/deactivate, or delete
   * roles — requires `role.manage` (see `usePermissions`). Someone with
   * only `role.view` can reach this list at all, but gets none of these
   * controls: View stays available either way since it needs no separate
   * gate (reaching this table at all already required `role.view` or
   * `role.manage`).
   */
  canManage: boolean
}

/** Role Name / Description / Status / Assigned Users / Actions — per the R1 wireframe. */
export function RolesTable({ roles, onToggleStatus, onDeleteRole, canManage }: RolesTableProps) {
  const { sorted, sortKey, direction, toggleSort } = useSortableTable<
    RoleRecord,
    SortKey
  >(roles, {
    name: (r) => r.name,
    description: (r) => r.description,
    status: (r) => r.status,
    assignedUsers: (r) => r.assignedUsers,
  })

  const [pendingDeleteRole, setPendingDeleteRole] = useState<RoleRecord | null>(null)

  return (
    <div className="overflow-hidden rounded-[14px] border border-border bg-card shadow-[var(--ledger-shadow)]">
      <div className="overflow-x-auto">
        <table className="w-full min-w-[760px] border-collapse">
          <thead>
            <tr className="border-b border-border">
              <SortableTh
                label="Role Name"
                active={sortKey === "name"}
                direction={direction}
                onClick={() => toggleSort("name")}
                className="px-3.5"
              />
              <SortableTh
                label="Description"
                active={sortKey === "description"}
                direction={direction}
                onClick={() => toggleSort("description")}
                className="px-3.5"
              />
              <SortableTh
                label="Status"
                active={sortKey === "status"}
                direction={direction}
                onClick={() => toggleSort("status")}
                className="px-3.5"
              />
              <SortableTh
                label="Assigned Users"
                active={sortKey === "assignedUsers"}
                direction={direction}
                onClick={() => toggleSort("assignedUsers")}
                className="px-3.5"
              />
              <th className="px-3.5 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
                Actions
              </th>
            </tr>
          </thead>
          <tbody>
            {roles.length === 0 && (
              <tr>
                <td
                  colSpan={5}
                  className="px-3.5 py-10 text-center text-[13px] text-muted-foreground"
                >
                  No roles match your search.
                </td>
              </tr>
            )}
            {sorted.map((role) => {
              const displayName = formatRoleNameForDisplay(role.name)
              return (
              <tr
                key={role.id}
                className="border-b border-border last:border-b-0 hover:bg-muted"
              >
                <td className="px-3.5 py-3 text-[13.5px] font-semibold text-primary">
                  {displayName}
                </td>
                <td className="max-w-[320px] truncate px-3.5 py-3 text-[13px] text-muted-foreground">
                  {role.description}
                </td>
                <td className="px-3.5 py-3">
                  <Badge
                    variant={role.status === "Active" ? "default" : "muted"}
                    className="gap-1.5 rounded-[10px]"
                  >
                    <span className="size-[5px] rounded-full bg-current" />
                    {role.status}
                  </Badge>
                </td>
                <td className="px-3.5 py-3 font-mono text-[12.5px] text-muted-foreground">
                  {role.assignedUsers}
                </td>
                <td className="px-3.5 py-3">
                  <div className="flex items-center gap-1">
                    <Tooltip>
                      <TooltipTrigger
                        render={(props) => (
                          <Link
                            {...props}
                            href={`/admin/roles/${role.id}`}
                            aria-label={`View ${displayName}`}
                            className="flex size-7 items-center justify-center rounded-[7px] text-primary transition-colors hover:bg-muted"
                          >
                            <Eye size={15} />
                          </Link>
                        )}
                      />
                      <TooltipContent>{getTooltip("roles.actions.view")}</TooltipContent>
                    </Tooltip>

                    {role.system ? (
                      <span className="inline-flex items-center gap-1.5 text-[12px] text-muted-foreground">
                        <ShieldCheck size={13} />
                        System role
                      </span>
                    ) : canManage ? (
                      <>
                        <Tooltip>
                          <TooltipTrigger
                            render={(props) => (
                              <Link
                                {...props}
                                href={`/admin/roles/${role.id}/edit`}
                                aria-label={`Edit ${displayName}`}
                                className="flex size-7 items-center justify-center rounded-[7px] text-primary transition-colors hover:bg-muted"
                              >
                                <Pencil size={15} />
                              </Link>
                            )}
                          />
                          <TooltipContent>{getTooltip("roles.actions.edit")}</TooltipContent>
                        </Tooltip>

                        <Tooltip>
                          <TooltipTrigger
                            render={(props) => (
                              <button
                                {...props}
                                type="button"
                                onClick={() => onToggleStatus(role.id)}
                                aria-label={
                                  role.status === "Active"
                                    ? `Deactivate ${displayName}`
                                    : `Activate ${displayName}`
                                }
                                className={cn(
                                  "flex size-7 items-center justify-center rounded-[7px] transition-colors",
                                  role.status === "Active"
                                    ? "text-[var(--rust)] hover:bg-[var(--rust-tint)]"
                                    : "text-primary hover:bg-muted"
                                )}
                              >
                                {role.status === "Active" ? (
                                  <UserX size={15} />
                                ) : (
                                  <UserCheck size={15} />
                                )}
                              </button>
                            )}
                          />
                          <TooltipContent>
                            {role.status === "Active"
                              ? getTooltip("roles.actions.deactivate")
                              : getTooltip("roles.actions.activate")}
                          </TooltipContent>
                        </Tooltip>

                        <Tooltip>
                          <TooltipTrigger
                            render={(props) => (
                              <button
                                {...props}
                                type="button"
                                onClick={() => setPendingDeleteRole(role)}
                                aria-label={`Delete ${displayName}`}
                                className="flex size-7 items-center justify-center rounded-[7px] text-[var(--rust)] transition-colors hover:bg-[var(--rust-tint)]"
                              >
                                <Trash2 size={15} />
                              </button>
                            )}
                          />
                          <TooltipContent>{getTooltip("roles.actions.delete")}</TooltipContent>
                        </Tooltip>
                      </>
                    ) : null}
                  </div>
                </td>
              </tr>
              )
            })}
          </tbody>
        </table>
      </div>

      {pendingDeleteRole && (
        <RoleDeleteDialog
          role={pendingDeleteRole}
          open={pendingDeleteRole !== null}
          onOpenChange={(open) => {
            if (!open) setPendingDeleteRole(null)
          }}
          onConfirm={() => onDeleteRole(pendingDeleteRole.id)}
        />
      )}
    </div>
  )
}