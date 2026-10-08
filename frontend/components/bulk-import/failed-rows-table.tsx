"use client"

import { Loader2, RotateCcw } from "lucide-react"

import { Button } from "@/components/ui/button"
import type { ImportRowFailureDto, ImportRowProblemDto } from "@/lib/bulk-imports/api"
import { useBulkImportFailures } from "@/lib/bulk-imports/use-bulk-import-failures"

import { InlineError } from "./step-card"

const COLUMNS = ["Row", "File", "Problem"]

/**
 * The rows of a finished import that failed, one problem per line. Scrolls
 * inside a fixed-height area (header stays put) so a 500-row batch doesn't
 * push the rest of the card off screen.
 */
export function FailedRowsTable({ batchId }: { batchId: string }) {
  const { failures, isLoading, error, refetch, isFetching } = useBulkImportFailures(batchId)

  return (
    <section className="mb-5">
      <h3 className="mb-2 text-[13px] font-semibold">Rows that failed</h3>
      {isLoading ? (
        <p className="inline-flex items-center gap-2 text-[13px] text-muted-foreground">
          <Loader2 size={14} className="animate-spin" aria-hidden="true" />
          Loading the rows that failed…
        </p>
      ) : error ? (
        <div>
          <InlineError>We couldn&apos;t load the rows that failed. Please try again.</InlineError>
          <Button variant="outline" size="sm" className="mt-2" onClick={() => refetch()} disabled={isFetching}>
            {isFetching ? <Loader2 className="animate-spin" /> : <RotateCcw />}
            Try again
          </Button>
        </div>
      ) : failures.length === 0 ? (
        <p className="text-[13px] text-muted-foreground">No individual rows were recorded as failed.</p>
      ) : (
        <div className="max-h-[420px] overflow-auto rounded-md border border-border">
          <table className="w-full min-w-[560px] border-collapse">
            <thead className="sticky top-0 z-10 bg-card">
              <tr className="border-b border-border">
                {COLUMNS.map((h) => (
                  <th
                    key={h}
                    className="px-3 py-2 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase"
                  >
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {failures.map((row) => (
                <FailedRow key={row.rowNumber} row={row} />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

function FailedRow({ row }: { row: ImportRowFailureDto }) {
  return (
    <tr className="border-b border-border align-top last:border-b-0">
      <td className="px-3 py-2.5 font-mono text-[12.5px] text-muted-foreground">{row.rowNumber}</td>
      <td className="px-3 py-2.5 text-[13px] break-all">
        {row.filename || "—"}
        {row.title && <div className="text-[12px] break-normal text-muted-foreground">{row.title}</div>}
      </td>
      <td className="px-3 py-2.5 text-[13px]">
        {row.problems.length === 0 ? (
          <span className="text-muted-foreground">No details were recorded.</span>
        ) : (
          row.problems.map((problem, i) => (
            <div key={i} className={problem.severity === "warning" ? "text-muted-foreground" : undefined}>
              {describe(problem)}
            </div>
          ))
        )}
      </td>
    </tr>
  )
}

function describe(problem: ImportRowProblemDto): string {
  return problem.fieldLabel ? `${problem.fieldLabel}: ${problem.message}` : problem.message
}
