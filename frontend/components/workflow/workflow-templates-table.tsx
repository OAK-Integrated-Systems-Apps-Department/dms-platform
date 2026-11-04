"use client"

import { ChevronRight } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import {
  ON_DOCUMENT_CHANGE_LABELS,
  TEMPLATE_STATUS_BADGE_VARIANT,
  TEMPLATE_STATUS_LABELS,
} from "@/lib/workflow/workflow-badges"
import { formatDateTime } from "@/lib/utils"

import type { WorkflowTemplateSummaryDto } from "@/lib/workflow/api"

interface WorkflowTemplatesTableProps {
  rows: WorkflowTemplateSummaryDto[]
  onView: (row: WorkflowTemplateSummaryDto) => void
}

const TH = "px-3 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase"

/**
 * One row per template VERSION (server-sorted, most recently updated
 * first) - a family's draft, active and retired versions each appear on
 * their own. Every row opens the template page.
 */
export function WorkflowTemplatesTable({ rows, onView }: WorkflowTemplatesTableProps) {
  return (
    <div className="overflow-hidden rounded-lg border border-border bg-card shadow-[var(--ledger-shadow)]">
      <div className="overflow-x-auto">
        <table className="w-full min-w-[880px] border-collapse">
          <thead>
            <tr className="border-b border-border">
              <th className={TH}>Name</th>
              <th className={TH}>Code</th>
              <th className={TH}>Version</th>
              <th className={TH}>Document Type</th>
              <th className={TH}>Stages</th>
              <th className={TH}>On Change</th>
              <th className={TH}>Status</th>
              <th className={TH}>Updated</th>
              <th className={TH} />
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && (
              <tr>
                <td colSpan={9} className="h-[max(160px,calc(100svh-400px))] px-3 py-10 text-center text-[13px] text-muted-foreground">
                  No workflow templates match your search or filters.
                </td>
              </tr>
            )}
            {rows.map((row) => (
              <tr
                key={row.templateId}
                onClick={() => onView(row)}
                className="cursor-pointer border-b border-border last:border-b-0 hover:bg-muted"
              >
                <td className="px-3 py-3 text-[13.5px] font-semibold text-primary">{row.name}</td>
                <td className="px-3 py-3 font-mono text-[12.5px] text-muted-foreground">{row.code}</td>
                <td className="px-3 py-3 text-[13px] text-foreground">v{row.versionNumber}</td>
                <td className="px-3 py-3 text-[13px] text-foreground">{row.documentTypeName ?? "—"}</td>
                <td className="px-3 py-3 text-[13px] text-foreground">{row.stageCount}</td>
                <td className="px-3 py-3 text-[13px] text-muted-foreground">{ON_DOCUMENT_CHANGE_LABELS[row.onDocumentChange]}</td>
                <td className="px-3 py-3">
                  <Badge variant={TEMPLATE_STATUS_BADGE_VARIANT[row.status]} className="gap-1.5 rounded-md">
                    <span className="size-[5px] rounded-full bg-current" />
                    {TEMPLATE_STATUS_LABELS[row.status]}
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
