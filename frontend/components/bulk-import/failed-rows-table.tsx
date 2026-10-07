"use client"

import { Loader2, RotateCcw } from "lucide-react"

import { Button } from "@/components/ui/button"
import { useBulkImportFailures } from "@/lib/bulk-imports/use-bulk-import-failures"

import { ImportRowsTable } from "./import-rows-table"
import { InlineError } from "./step-card"

/** The files of a finished import that failed - rejected uploads included - loaded from the server. */
export function FailedRowsTable({ batchId }: { batchId: string }) {
  const { failures, isLoading, error, refetch, isFetching } = useBulkImportFailures(batchId)

  return (
    <section className="mb-5">
      <h3 className="mb-2 text-[13px] font-semibold">Files that failed</h3>
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
