"use client"

import { useState } from "react"

import { RunStatusBadge } from "@/components/reports/run-status-badge"
import { Button } from "@/components/ui/button"
import { LoadFailed } from "@/components/ui/load-failed"
import { LoadingState } from "@/components/ui/loading-state"
import { TablePagination } from "@/components/ui/table-pagination"
import { formatInZone, FORMAT_LABELS } from "@/lib/reports/format"
import { useCancelRun, useDownloadRun, useRetryRun, useRuns } from "@/lib/reports/use-reports"

const STATUSES = [
  { value: "", label: "All" },
  { value: "queued", label: "Waiting" },
  { value: "running", label: "Running" },
  { value: "completed", label: "Ready" },
  { value: "failed", label: "Failed" },
  { value: "cancelled", label: "Cancelled" },
  { value: "expired", label: "Expired" },
]

/**
 * Every run the person asked for or was sent. Open runs refresh by themselves (slower the longer they take), so a finished report
 * appears here without reloading.
 */
export function RunHistoryTab() {
  const [status, setStatus] = useState("")
  const [page, setPage] = useState(0)
  const runs = useRuns({ status: status || undefined, page })
  const download = useDownloadRun()
  const cancel = useCancelRun()
  const retry = useRetryRun()

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center gap-2">
        <label htmlFor="run-status" className="text-[12.5px] text-muted-foreground">
          Show
        </label>
        <select
          id="run-status"
          value={status}
          onChange={(e) => {
            setStatus(e.target.value)
            setPage(0)
          }}
          className="h-9 rounded-btn border border-border bg-background px-2 text-[13px]"
        >
          {STATUSES.map((s) => (
            <option key={s.value} value={s.value}>
              {s.label}
            </option>
          ))}
        </select>
      </div>

      {runs.isPending ? (
        <LoadingState message="Loading your report runs…" />
      ) : runs.isError ? (
        <LoadFailed error={runs.error} fallback="Could not load your report runs." onRetry={() => void runs.refetch()} />
      ) : runs.data.content.length === 0 ? (
        <div className="rounded-lg border border-dashed border-[var(--border-strong)] bg-card px-6 py-12 text-center text-[13px] text-muted-foreground">
          No report runs{status ? " with that status" : " yet"}.
        </div>
      ) : (
        <>
          <div className="overflow-x-auto rounded-lg border border-border bg-card">
            <table className="w-full text-left text-[13px]">
              <thead className="border-b border-border text-[12px] text-muted-foreground">
                <tr>
                  <th className="px-4 py-2.5 font-medium">Report</th>
                  <th className="px-4 py-2.5 font-medium">Status</th>
                  <th className="px-4 py-2.5 font-medium">Format</th>
                  <th className="px-4 py-2.5 font-medium">Rows</th>
                  <th className="px-4 py-2.5 font-medium">Created</th>
                  <th className="px-4 py-2.5 font-medium">Expires</th>
                  <th className="px-4 py-2.5 text-right font-medium">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {runs.data.content.map((r) => (
                  <tr key={r.runId}>
                    <td className="px-4 py-3">
                      <div className="font-medium">{r.title ?? "Custom report"}</div>
                      {r.status === "failed" && r.errorMessage && <div className="text-[12.5px] text-destructive">{r.errorMessage}</div>}
                      {r.truncated && <div className="text-[12px] text-muted-foreground">Limited to the first rows.</div>}
                    </td>
                    <td className="px-4 py-3">
                      <RunStatusBadge status={r.status} />
                      {r.status === "running" && r.progressRows > 0 && (
                        <span className="ml-2 text-[12px] text-muted-foreground">{r.progressRows.toLocaleString()} rows</span>
                      )}
                    </td>
                    <td className="px-4 py-3">{FORMAT_LABELS[r.format] ?? r.format}</td>
                    <td className="px-4 py-3">{r.rowCount === null ? "—" : r.rowCount.toLocaleString()}</td>
                    <td className="px-4 py-3">{formatInZone(r.createdAt)}</td>
                    <td className="px-4 py-3">{formatInZone(r.expiresAt)}</td>
                    <td className="px-4 py-3">
                      <div className="flex justify-end gap-1.5">
                        {r.downloadable && (
                          <Button size="xs" disabled={download.isPending} onClick={() => download.mutate(r.runId)}>
                            Download
                          </Button>
                        )}
                        {(r.status === "failed" || r.status === "expired") && (
                          <Button size="xs" variant="outline" disabled={retry.isPending} onClick={() => retry.mutate(r.runId)}>
                            Retry
                          </Button>
                        )}
                        {(r.status === "queued" || r.status === "running") && (
                          <Button size="xs" variant="outline" disabled={cancel.isPending} onClick={() => cancel.mutate(r.runId)}>
                            Cancel
                          </Button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <TablePagination currentPage={page + 1} totalPages={runs.data.totalPages} onPageChange={(p) => setPage(p - 1)} />
        </>
      )}
    </div>
  )
}
