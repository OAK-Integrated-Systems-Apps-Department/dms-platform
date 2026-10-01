"use client"

import { useQuery } from "@tanstack/react-query"
import { Loader2 } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { fetchMyBulkImports, isFinished, type BulkImportStatus } from "@/lib/bulk-imports/api"
import { BULK_IMPORTS_KEY } from "@/lib/bulk-imports/use-bulk-import"
import { formatDateTime } from "@/lib/utils"

const STATUS: Record<BulkImportStatus, { label: string; variant: "default" | "muted" | "gold" | "rust" | "outline" }> = {
  uploading: { label: "Uploading", variant: "muted" },
  queued: { label: "Queued", variant: "muted" },
  running: { label: "Processing", variant: "gold" },
  completed: { label: "Completed", variant: "default" },
  completed_with_errors: { label: "Completed with errors", variant: "gold" },
  failed: { label: "Failed", variant: "rust" },
  cancelled: { label: "Cancelled", variant: "outline" },
}

const TH = "px-3 py-2 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase"

/**
 * The caller's latest imports, finished ones included, so a result (and
 * its failed-rows file) can be reopened after leaving the page. Lives under
 * the ["bulk-imports"] key, so it refreshes whenever an import changes.
 */
export function RecentImports({
  documentTypeName,
  onOpen,
}: {
  documentTypeName: (documentTypeId: string) => string | null
  onOpen: (batchId: string) => void
}) {
  const { data, isPending, isError } = useQuery({
    queryKey: [...BULK_IMPORTS_KEY, "mine"],
    queryFn: () => fetchMyBulkImports(0, 10),
  })
  const rows = data?.content ?? []

  if (isPending) {
    return (
      <p className="inline-flex items-center gap-2 text-[13px] text-muted-foreground">
        <Loader2 size={14} className="animate-spin" aria-hidden="true" />
        Loading your recent imports…
      </p>
    )
  }
  if (isError || rows.length === 0) {
    return null
  }

  return (
    <section>
      <h2 className="mb-2 font-heading text-[16px] font-semibold">Your recent imports</h2>
      <div className="overflow-x-auto rounded-lg border border-border bg-card shadow-[var(--ledger-shadow)]">
        <table className="w-full min-w-[680px] border-collapse">
          <thead>
            <tr className="border-b border-border">
              <th className={TH}>Started</th>
              <th className={TH}>Document type</th>
              <th className={TH}>Status</th>
              <th className={TH}>Result</th>
              <th className={TH}>
                <span className="sr-only">Open</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.batchId} className="border-b border-border last:border-b-0">
                <td className="px-3 py-2.5 font-mono text-[12.5px] text-muted-foreground">
                  {formatDateTime(row.createdAt)}
                </td>
                <td className="px-3 py-2.5 text-[13px]">{documentTypeName(row.documentTypeId) ?? "—"}</td>
                <td className="px-3 py-2.5">
                  <Badge variant={STATUS[row.status].variant} className="rounded-md">
                    {STATUS[row.status].label}
                  </Badge>
                </td>
                <td className="px-3 py-2.5 text-[12.5px] text-muted-foreground">
                  {isFinished(row.status)
                    ? `${row.successCount} created, ${row.failureCount} failed of ${row.totalRecords}`
                    : `${row.filesReceived} of ${row.totalRecords} files`}
                </td>
                <td className="px-3 py-2.5 text-right">
                  {row.status !== "cancelled" && (
                    <Button variant="outline" size="sm" onClick={() => onOpen(row.batchId)}>
                      {isFinished(row.status) ? "View results" : "Open"}
                    </Button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}
