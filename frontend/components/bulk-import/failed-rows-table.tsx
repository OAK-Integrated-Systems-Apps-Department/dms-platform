"use client"

import { useState } from "react"
import { Download, Loader2, RotateCcw } from "lucide-react"

import { Button } from "@/components/ui/button"
import { friendlyMessage } from "@/lib/api-error"
import { exportBulkImportFailuresCsv } from "@/lib/bulk-imports/api"
import { useBulkImportFailures } from "@/lib/bulk-imports/use-bulk-import-failures"

import { ImportRowsTable } from "./import-rows-table"
import { InlineError } from "./step-card"

/** The files of a finished import that failed - rejected uploads included - loaded from the server. */
export function FailedRowsTable({ batchId }: { batchId: string }) {
  const { failures, isLoading, error, refetch, isFetching } = useBulkImportFailures(batchId)
  const [exporting, setExporting] = useState(false)
  const [exportError, setExportError] = useState<string | null>(null)

  async function handleExport() {
    setExporting(true)
    setExportError(null)
    try {
      await exportBulkImportFailuresCsv(batchId)
    } catch (err) {
      setExportError(friendlyMessage(err, "Could not export the failure report. Please try again."))
    } finally {
      setExporting(false)
    }
  }

  return (
    <section className="mb-5">
      <div className="mb-2 flex items-center justify-between gap-3">
        <h3 className="text-[13px] font-semibold">Files that failed</h3>
        {failures.length > 0 && (
          <Button variant="outline" size="sm" onClick={handleExport} disabled={exporting}>
            {exporting ? <Loader2 className="animate-spin" /> : <Download />}
            {exporting ? "Exporting…" : "Export as CSV"}
          </Button>
        )}
      </div>
      {exportError && (
        <div className="mb-3">
          <InlineError>{exportError}</InlineError>
        </div>
      )}
      {isLoading ? (
        <p className="inline-flex items-center gap-2 text-[13px] text-muted-foreground">
          <Loader2 size={14} className="animate-spin" aria-hidden="true" />
          Loading the files that failed…
        </p>
      ) : error ? (
        <div>
          <InlineError>We couldn&apos;t load the files that failed. Please try again.</InlineError>
          <Button variant="outline" size="sm" className="mt-2" onClick={() => refetch()} disabled={isFetching}>
            {isFetching ? <Loader2 className="animate-spin" /> : <RotateCcw />}
            Try again
          </Button>
        </div>
      ) : failures.length === 0 ? (
        <p className="text-[13px] text-muted-foreground">No individual files were recorded as failed.</p>
      ) : (
        <ImportRowsTable rows={failures} />
      )}
    </section>
  )
}
