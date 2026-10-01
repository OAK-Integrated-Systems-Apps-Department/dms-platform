"use client"

import { AlertTriangle, CheckCircle2, Loader2 } from "lucide-react"

import { useBulkImportRowCheck } from "@/lib/bulk-imports/use-bulk-import-row-check"

/**
 * "Check before you submit": the template's rows that will fail (errors)
 * or only be flagged (warnings) once processed, from the same checks the
 * import worker runs. A row that will fail can be fixed by cancelling and
 * uploading a corrected template; the rest import either way.
 */
export function RowCheckPanel({ batchId, totalRecords }: { batchId: string; totalRecords: number }) {
  const { problems, isLoading, isError } = useBulkImportRowCheck(batchId)

  if (isLoading) {
    return (
      <p className="inline-flex items-center gap-2 text-[13px] text-muted-foreground">
        <Loader2 size={14} className="animate-spin" aria-hidden="true" />
        Checking the template&apos;s rows…
      </p>
    )
  }
  if (isError) {
    return <p className="text-[13px] text-muted-foreground">The rows couldn&apos;t be checked now - they&apos;ll be checked after you submit.</p>
  }

  const failing = problems.filter((row) => row.problems.some((p) => p.severity === "error"))
  if (problems.length === 0) {
    return (
      <p className="inline-flex items-center gap-2 text-[13px] text-foreground">
        <CheckCircle2 size={15} className="text-primary" aria-hidden="true" />
        All {totalRecords} {totalRecords === 1 ? "row looks" : "rows look"} fine.
      </p>
    )
  }

  return (
    <div>
      <p className="mb-2 inline-flex items-center gap-2 text-[13px] text-foreground">
        <AlertTriangle size={15} className="text-[var(--gold)]" aria-hidden="true" />
        {failing.length > 0
          ? `${failing.length} ${failing.length === 1 ? "row" : "rows"} will fail as they are. Cancel and upload a corrected template, or submit and fix them afterwards from the failed-rows file.`
          : "Some rows have warnings - they'll still be imported."}
      </p>
      <ul className="max-h-[260px] divide-y divide-border overflow-y-auto rounded-md border border-border text-[12.5px]">
        {problems.map((row) => (
          <li key={row.rowNumber} className="px-3.5 py-2">
            <span className="font-mono text-muted-foreground">Row {row.rowNumber}</span>
            {row.filename && <span className="ml-2 break-all">{row.filename}</span>}
            {row.problems.map((problem, i) => (
              <div
                key={i}
                className={problem.severity === "warning" ? "text-muted-foreground" : "text-[var(--rust)]"}
              >
                {problem.fieldLabel ? `${problem.fieldLabel}: ` : ""}
                {problem.message}
                {problem.severity === "warning" && " (warning)"}
              </div>
            ))}
          </li>
        ))}
      </ul>
    </div>
  )
}
