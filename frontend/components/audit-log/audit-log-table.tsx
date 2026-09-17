"use client"

import { ChevronRight } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { SortableTh } from "@/components/ui/sortable-th"
import { formatDateTime } from "@/lib/utils"

import type { AuditEventResponseDto } from "@/lib/audit-events/api"

export type AuditLogSortField = "occurredAt" | "action" | "module" | "outcome" | "severity"

interface AuditLogTableProps {
  rows: AuditEventResponseDto[]
  onView: (row: AuditEventResponseDto) => void
  sortField: AuditLogSortField
  sortDirection: "asc" | "desc"
  onSortChange: (field: AuditLogSortField) => void
}

const OUTCOME_VARIANT: Record<AuditEventResponseDto["outcome"], "default" | "rust"> = {
  SUCCESS: "default",
  FAILURE: "rust",
  DENIED: "rust",
}

const SEVERITY_VARIANT: Record<NonNullable<AuditEventResponseDto["severity"]>, "muted" | "gold" | "rust"> = {
  LOW: "muted",
  MEDIUM: "gold",
  HIGH: "rust",
}

/**
 * Occurred At / Action / Module / Object / Actor / Outcome / Severity,
 * server-sorted (not `useSortableTable` - see `AuditLogSortField`, which
 * maps straight onto the backend's `sort` query param) since
 * `GET /api/v1/audit-events` is real server-side pagination, unlike
 * Users/Roles which sort an already-fully-fetched array client-side.
 * Every row is clickable (and additionally has a chevron affordance) -
 * routes to the detail page (`/admin/audit-log/[id]`), which is where
 * previousValue/newValue actually live (deliberately excluded from this
 * list response).
 */
export function AuditLogTable({ rows, onView, sortField, sortDirection, onSortChange }: AuditLogTableProps) {
  return (
    <div className="overflow-hidden rounded-lg border border-border bg-card shadow-[var(--ledger-shadow)]">
      <div className="overflow-x-auto">
        <table className="w-full min-w-[920px] border-collapse">
          <thead>
            <tr className="border-b border-border">
              <SortableTh
                label="Occurred At"
                active={sortField === "occurredAt"}
                direction={sortDirection}
                onClick={() => onSortChange("occurredAt")}
              />
              <SortableTh
                label="Action"
                active={sortField === "action"}
                direction={sortDirection}
                onClick={() => onSortChange("action")}
              />
              <SortableTh
                label="Module"
                active={sortField === "module"}
                direction={sortDirection}
                onClick={() => onSortChange("module")}
              />
              <th className="px-3 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
                Object
              </th>
              <th className="px-3 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
                Actor
              </th>
              <SortableTh
                label="Outcome"
                active={sortField === "outcome"}
                direction={sortDirection}
                onClick={() => onSortChange("outcome")}
              />
              <SortableTh
                label="Severity"
                active={sortField === "severity"}
                direction={sortDirection}
                onClick={() => onSortChange("severity")}
              />
              <th className="px-3 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase" />
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && (
              <tr>
                <td colSpan={8} className="px-3 py-10 text-center text-[13px] text-muted-foreground">
                  No audit events match your search or filters.
                </td>
              </tr>
            )}
            {rows.map((row) => (
              <tr
                key={row.id}
                onClick={() => onView(row)}
                className="cursor-pointer border-b border-border last:border-b-0 hover:bg-muted"
              >
                <td className="px-3 py-3 font-mono text-[12.5px] text-muted-foreground">
                  {formatDateTime(row.occurredAt)}
                </td>
                <td className="px-3 py-3 text-[13.5px] font-semibold text-primary">{row.action}</td>
                <td className="px-3 py-3 text-[13px] text-muted-foreground">{row.module}</td>
                <td className="px-3 py-3 text-[13px] text-foreground">
                  <div className="flex flex-col">
                    <span>{row.objectType}</span>
                    {row.resourceName && (
                      <span className="text-[12px] text-muted-foreground">{row.resourceName}</span>
                    )}
                  </div>
                </td>
                <td className="px-3 py-3 text-[13px] text-foreground">
                  {row.actorDisplayName ?? (row.actorType === "SYSTEM" ? "System" : "—")}
                </td>
                <td className="px-3 py-3">
                  <Badge variant={OUTCOME_VARIANT[row.outcome]} className="gap-1.5 rounded-md">
                    <span className="size-[5px] rounded-full bg-current" />
                    {row.outcome}
                  </Badge>
                </td>
                <td className="px-3 py-3">
                  {row.severity && <Badge variant={SEVERITY_VARIANT[row.severity]}>{row.severity}</Badge>}
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
