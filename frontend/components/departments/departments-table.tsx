"use client"

import { ChevronRight } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { formatDateTime } from "@/lib/utils"

import type { DepartmentDto } from "@/lib/departments/api"

interface DepartmentsTableProps {
  rows: DepartmentDto[]
  onView: (row: DepartmentDto) => void
}

const TH = "px-3 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase"

/** Name / Code / Parent / Head / Members / Status, sorted by name server-side. Rows open the edit page. */
export function DepartmentsTable({ rows, onView }: DepartmentsTableProps) {
  return (
    <div className="overflow-hidden rounded-lg border border-border bg-card shadow-[var(--ledger-shadow)]">
      <div className="overflow-x-auto">
        <table className="w-full min-w-[820px] border-collapse">
          <thead>
            <tr className="border-b border-border">
              <th className={TH}>Name</th>
              <th className={TH}>Code</th>
              <th className={TH}>Parent</th>
              <th className={TH}>Head</th>
              <th className={TH}>Members</th>
              <th className={TH}>Status</th>
              <th className={TH}>Updated</th>
              <th className={TH} />
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && (
              <tr>
                <td colSpan={8} className="px-3 py-10 text-center text-[13px] text-muted-foreground">
                  No departments match your search or filters.
                </td>
              </tr>
            )}
            {rows.map((row) => (
              <tr
                key={row.departmentId}
                onClick={() => onView(row)}
                className="cursor-pointer border-b border-border last:border-b-0 hover:bg-muted"
              >
                <td className="px-3 py-3 text-[13.5px] font-semibold text-primary">{row.name}</td>
                <td className="px-3 py-3 font-mono text-[12.5px] text-muted-foreground">{row.code}</td>
                <td className="px-3 py-3 text-[13px] text-foreground">{row.parentName ?? "—"}</td>
                <td className="px-3 py-3 text-[13px] text-foreground">{row.headUserName ?? "—"}</td>
                <td className="px-3 py-3 text-[13px] text-foreground">{row.memberCount}</td>
                <td className="px-3 py-3">
                  <Badge variant={row.status === "active" ? "default" : "muted"} className="gap-1.5 rounded-md">
                    <span className="size-[5px] rounded-full bg-current" />
                    {row.status === "active" ? "Active" : "Inactive"}
                  </Badge>
                </td>
                <td className="px-3 py-3 font-mono text-[12.5px] text-muted-foreground">
                  {formatDateTime(row.updatedAt ?? row.createdAt)}
                </td>
                <td className="px-3 py-3 text-right">
                  <ChevronRight size={15} className="inline text-muted-foreground" />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
