"use client"

import Link from "next/link"
import { Pencil, ShieldCheck } from "lucide-react"

import { cn } from "@/lib/utils"

import { RoleStatusBadge } from "./role-status-badge"
import type { RoleRecord } from "./types"

interface RolesTableProps {
  roles: RoleRecord[]
  onToggleStatus: (id: string) => void
}

/** Role Name / Description / Status / Assigned Users / Actions — per the R1 wireframe. */
export function RolesTable({ roles, onToggleStatus }: RolesTableProps) {
  return (
    <div className="overflow-hidden rounded-[14px] border border-border bg-card shadow-[var(--ledger-shadow)]">
      <div className="overflow-x-auto">
        <table className="w-full min-w-[760px] border-collapse">
          <thead>
            <tr className="border-b border-border">
              <th className="px-3.5 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
                Role Name
              </th>
              <th className="px-3.5 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
                Description
              </th>
              <th className="px-3.5 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
                Status
              </th>
              <th className="px-3.5 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
                Assigned Users
              </th>
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
            {roles.map((role) => (
              <tr
                key={role.id}
                className="border-b border-border last:border-b-0 hover:bg-muted"
              >
                <td className="px-3.5 py-3 text-[13.5px] font-semibold text-primary">
                  {role.name}
                </td>
                <td className="max-w-[320px] truncate px-3.5 py-3 text-[13px] text-muted-foreground">
                  {role.description}
                </td>
                <td className="px-3.5 py-3">
                  <RoleStatusBadge status={role.status} />
                </td>
                <td className="px-3.5 py-3 font-mono text-[12.5px] text-muted-foreground">
                  {role.assignedUsers}
                </td>
                <td className="px-3.5 py-3">
                  {role.system ? (
                    <span className="inline-flex items-center gap-1.5 text-[12px] text-muted-foreground">
                      <ShieldCheck size={13} />
                      System role
                    </span>
                  ) : (
                    <div className="flex items-center gap-3.5">
                      <Link
                        href={`/admin/roles/${role.id}/edit`}
                        className="flex items-center gap-1 text-[12.5px] font-semibold text-primary hover:underline"
                      >
                        <Pencil size={13} />
                        Edit
                      </Link>
                      <button
                        type="button"
                        onClick={() => onToggleStatus(role.id)}
                        className={cn(
                          "text-[12.5px] font-semibold hover:underline",
                          role.status === "Active"
                            ? "text-[var(--rust)]"
                            : "text-primary"
                        )}
                      >
                        {role.status === "Active" ? "Deactivate" : "Activate"}
                      </button>
                    </div>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
