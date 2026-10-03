"use client"

import { ChevronRight, Loader2, RotateCcw } from "lucide-react"

import { Button } from "@/components/ui/button"
import { friendlyMessage } from "@/lib/api-error"
import { isFinished, type BulkImportSummaryDto } from "@/lib/bulk-imports/api"
import { useRecentBulkImports } from "@/lib/bulk-imports/use-recent-bulk-imports"
import { cn } from "@/lib/utils"

import { formatDateTime } from "./batch-status-card"
import { ImportStatusBadge } from "./import-status-badge"
import { InlineError, StepCard } from "./step-card"

const COLUMNS = ["Date", "Document type", "Status", "Result"]

interface RecentImportsProps {
  /** The batch open above, highlighted in the list. */
  selectedBatchId: string | null
  onOpen: (summary: BulkImportSummaryDto) => void
  /** True while files are still uploading - opening another batch would drop them. */
  disabled?: boolean
}

/**
 * The caller's imports, newest first, 10 at a time with "Load more". Read-only:
 * a row opens that batch in the wizard above. Hidden once loaded if there
 * are none.
 */
export function RecentImports({ selectedBatchId, onOpen, disabled = false }: RecentImportsProps) {
  const { imports, isLoading, error, refetch, isFetching, hasNextPage, fetchNextPage, isFetchingNextPage } =
    useRecentBulkImports()

  if (!isLoading && !error && imports.length === 0) return null

  return (
    <StepCard
      title="Recent imports"
      description={disabled ? "Wait for your files to finish uploading to open another import." : undefined}
    >
      {isLoading ? (
        <p className="inline-flex items-center gap-2 text-[13px] text-muted-foreground">
          <Loader2 size={14} className="animate-spin" aria-hidden="true" />
          Loading your recent imports…
        </p>
      ) : error && imports.length === 0 ? (
        <div>
          <InlineError>{friendlyMessage(error, "We couldn't load your recent imports. Please try again.")}</InlineError>
          <Button variant="outline" size="sm" className="mt-2" onClick={() => refetch()} disabled={isFetching}>
            {isFetching ? <Loader2 className="animate-spin" /> : <RotateCcw />}
            Try again
          </Button>
        </div>
      ) : (
        <>
          <div className="overflow-auto rounded-md border border-border">
            <table className="w-full min-w-[560px] border-collapse">
              <thead className="bg-card">
                <tr className="border-b border-border">
                  {COLUMNS.map((h) => (
                    <th
                      key={h}
                      className="px-3 py-2 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase"
                    >
                      {h}
                    </th>
                  ))}
                  <th className="px-3 py-2" />
                </tr>
              </thead>
              <tbody>
                {imports.map((row) => (
                  <RecentImportRow
                    key={row.batchId}
                    row={row}
                    selected={row.batchId === selectedBatchId}
                    disabled={disabled}
                    onOpen={onOpen}
                  />
                ))}
              </tbody>
            </table>
          </div>

          {error && (
            <InlineError>{friendlyMessage(error, "We couldn't load more imports. Please try again.")}</InlineError>
          )}
          {hasNextPage && (
            <Button
              variant="outline"
              size="sm"
              className="mt-3"
              onClick={() => fetchNextPage()}
              disabled={isFetchingNextPage}
            >
              {isFetchingNextPage && <Loader2 className="animate-spin" />}
              {isFetchingNextPage ? "Loading…" : "Load more"}
            </Button>
          )}
        </>
      )}
    </StepCard>
  )
}

function RecentImportRow({
  row,
  selected,
  disabled,
  onOpen,
}: {
  row: BulkImportSummaryDto
  selected: boolean
  disabled: boolean
  onOpen: (summary: BulkImportSummaryDto) => void
}) {
  function open() {
    if (!disabled) onOpen(row)
  }

  return (
    <tr
      tabIndex={disabled ? -1 : 0}
      aria-disabled={disabled || undefined}
      aria-current={selected || undefined}
      onClick={open}
      onKeyDown={(event) => {
        if (event.key === "Enter" || event.key === " ") {
          event.preventDefault()
          open()
        }
      }}
      className={cn(
        "border-b border-border last:border-b-0 focus-visible:bg-muted focus-visible:outline-none",
        disabled ? "cursor-not-allowed opacity-60" : "cursor-pointer hover:bg-muted",
        selected && "bg-muted"
      )}
    >
      <td className="px-3 py-2.5 font-mono text-[12.5px] whitespace-nowrap text-muted-foreground">
        {formatDateTime(row.createdAt)}
      </td>
      <td className="px-3 py-2.5 text-[13px] font-medium">{row.documentTypeName ?? "—"}</td>
      <td className="px-3 py-2.5">
        <ImportStatusBadge status={row.status} />
      </td>
      <td className="px-3 py-2.5 text-[13px]">
        {isFinished(row.status) ? `${row.successCount} of ${row.totalRecords} created` : "—"}
      </td>
      <td className="px-3 py-2.5 text-right">
        <ChevronRight size={15} className="inline text-muted-foreground" aria-hidden="true" />
      </td>
    </tr>
  )
}
