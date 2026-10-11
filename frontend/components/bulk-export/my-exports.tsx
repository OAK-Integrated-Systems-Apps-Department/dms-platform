"use client"

import { useState } from "react"
import { Download, Loader2 } from "lucide-react"
import { toast } from "sonner"

import { InlineError, StepCard } from "@/components/bulk-import/step-card"
import { Button } from "@/components/ui/button"
import { TablePagination } from "@/components/ui/table-pagination"
import { ApiError } from "@/lib/api-error"
import { downloadExport, exportErrorMessage, type BulkExportJobDto } from "@/lib/bulk-exports/api"
import { formatRelativeTime } from "@/lib/bulk-exports/format"
import { useMyExports } from "@/lib/bulk-exports/use-bulk-exports"
import { formatDateTime } from "@/lib/utils"

import { ExportStatusBadge } from "./export-status-badge"

const PAGE_SIZE = 10
const TH = "px-3 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase"
const TD = "px-3 py-3 text-[13px] text-foreground"

function expiresLabel(job: BulkExportJobDto): string {
  if (job.status === "expired") return "Expired"
  if (!job.expiresAt) return "—"
  return new Date(job.expiresAt).getTime() <= Date.now() ? "Expired" : formatRelativeTime(job.expiresAt)
}

/** The caller's exports, newest first; polls (in `useMyExports`) while one is queued or being built. */
export function MyExports() {
  const [page, setPage] = useState(1)
  const { data, isPending, isError, error, refetch } = useMyExports(page, PAGE_SIZE)
  const [downloading, setDownloading] = useState<string | null>(null)

  async function handleDownload(job: BulkExportJobDto) {
    setDownloading(job.exportJobId)
    try {
      await downloadExport(job.exportJobId)
    } catch (e) {
      toast.error(exportErrorMessage(e))
      // Expired or no longer ready since the list loaded - show its real status.
      if (e instanceof ApiError && (e.status === 409 || e.status === 410)) void refetch()
    } finally {
      setDownloading(null)
    }
  }

  const jobs = data?.content ?? []

  return (
    <StepCard
      title="My exports"
      description="Exports are built in the background. Each ZIP can be downloaded until it expires."
      className="mt-6"
    >
      {isPending ? (
        <p className="flex items-center gap-2 text-[13px] text-muted-foreground">
          <Loader2 size={15} className="animate-spin" aria-hidden="true" /> Loading your exports…
        </p>
      ) : isError ? (
        <InlineError>
          {exportErrorMessage(error)}{" "}
          <button type="button" onClick={() => void refetch()} className="font-semibold underline">
            Try again
          </button>
        </InlineError>
      ) : jobs.length === 0 ? (
        <p className="text-[13px] text-muted-foreground">You haven&apos;t exported anything yet.</p>
      ) : (
        <>
          <div className="overflow-x-auto">
            <table className="w-full min-w-[820px] border-collapse">
              <thead>
                <tr className="border-b border-border">
                  <th className={TH}>Status</th>
                  <th className={TH}>Exported</th>
                  <th className={TH}>Skipped</th>
                  <th className={TH}>Format</th>
                  <th className={TH}>Created</th>
                  <th className={TH}>Expires</th>
                  <th className={TH} />
                </tr>
              </thead>
              <tbody>
                {jobs.map((job) => (
                  <tr key={job.exportJobId} className="border-b border-border align-top last:border-b-0">
                    <td className="px-3 py-3">
                      <ExportStatusBadge status={job.status} />
                      {job.status === "failed" && job.failureReason && (
                        <p className="mt-1.5 max-w-[280px] text-[12px] leading-[1.5] text-[var(--rust)]">
                          {job.failureReason}
                        </p>
                      )}
                    </td>
                    <td className={TD}>{job.documentCount.toLocaleString()}</td>
                    <td className={TD}>{job.skippedCount.toLocaleString()}</td>
                    <td className={TD}>{job.metadataFormat === "xlsx" ? "Excel" : "CSV"}</td>
                    <td className="px-3 py-3 font-mono text-[12.5px] text-muted-foreground">
                      {formatDateTime(job.createdAt)}
                    </td>
                    <td className="px-3 py-3 text-[13px] text-muted-foreground" title={job.expiresAt ? formatDateTime(job.expiresAt) : undefined}>
                      {expiresLabel(job)}
                    </td>
                    <td className="px-3 py-3 text-right">
                      {job.downloadAvailable && (
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() => void handleDownload(job)}
                          disabled={downloading === job.exportJobId}
                        >
                          <Download aria-hidden="true" />
                          {downloading === job.exportJobId ? "Downloading…" : "Download"}
                        </Button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <TablePagination currentPage={page} totalPages={data?.totalPages ?? 1} onPageChange={setPage} />
        </>
      )}
    </StepCard>
  )
}
