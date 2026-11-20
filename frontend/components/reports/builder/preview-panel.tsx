"use client"

import { useEffect, useState } from "react"
import { AlertTriangle, ShieldCheck } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Skeleton } from "@/components/ui/skeleton"
import type { CatalogDto } from "@/lib/reports/api"
import { summarise, type ReportDefinition } from "@/lib/reports/definition"
import { reportError } from "@/lib/reports/messages"
import { useReportPreview } from "@/lib/reports/use-reports"

export const PREVIEW_DEBOUNCE_MS = 500

function useDebounced<T>(value: T, ms: number): T {
  const [debounced, setDebounced] = useState(value)
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), ms)
    return () => clearTimeout(timer)
  }, [value, ms])
  return debounced
}

interface Props {
  definition: ReportDefinition
  catalog: CatalogDto
  format: string
  /** The definition passes validation; the preview is only asked for then. */
  valid: boolean
  labels: Record<string, string>
  /** Told when the chosen format cannot hold what the report matches, so the page can block Run. */
  onTooLarge?: (tooLarge: boolean) => void
}

/** The sticky side panel: the report in a sentence, how many documents match, and the first rows. */
export function PreviewPanel({ definition, catalog, format, valid, labels, onTooLarge }: Props) {
  const debounced = useDebounced(definition, PREVIEW_DEBOUNCE_MS)
  const query = useReportPreview(debounced, valid)
  const { data, isLoading, isFetching, isError, error, refetch } = query

  const limit = format === "pdf" ? (data?.maxPdfRows ?? catalog.settings.limits.maxPdfRows) : (data?.maxRows ?? catalog.settings.limits.maxRows)
  const total = data?.total ?? null
  const over = valid && total !== null && total > limit
  const truncates = over && format !== "pdf" && catalog.settings.overLimitBehaviour === "truncate_and_mark"

  useEffect(() => {
    onTooLarge?.(Boolean(over && !truncates))
  }, [over, truncates, onTooLarge])

  return (
    <aside aria-label="Report summary and preview" className="space-y-4 rounded-lg border border-border bg-card p-5 shadow-[var(--ledger-shadow)]">
      <div>
        <h2 className="mb-1 text-[11px] font-semibold tracking-[0.07em] text-muted-foreground uppercase">This report</h2>
        <p className="text-[13.5px] leading-[1.6]" data-testid="report-sentence">
          {summarise(definition, catalog, labels)}
        </p>
      </div>

      {!valid && <p className="text-[13px] text-muted-foreground">Finish the highlighted parts to see a preview.</p>}

      {valid && (isLoading || (isFetching && !data)) && (
        <div role="status" aria-label="Loading preview" className="space-y-2">
          <Skeleton className="h-5 w-40" />
          <Skeleton className="h-24 w-full" />
        </div>
      )}

      {valid && isError && (
        <div role="alert" className="rounded-btn border border-destructive/40 p-3 text-[13px]">
          <p className="text-destructive">{reportError(error, "Could not preview the report.")}</p>
          <Button type="button" variant="outline" size="xs" className="mt-2" onClick={() => void refetch()}>
            Retry
          </Button>
        </div>
      )}

      {valid && data && (
        <div className="space-y-3">
          <p className="text-[13.5px] font-medium" data-testid="report-total">
            {data.timedOut || total === null ? "Too large to count quickly" : `${total.toLocaleString()} ${total === 1 ? "document matches" : "documents match"}`}
          </p>

          {data.limitedToAccess && (
            <p className="flex items-start gap-2 text-[12.5px] text-muted-foreground">
              <ShieldCheck aria-hidden className="mt-0.5 size-4 shrink-0" />
              Limited to the documents you have access to. Someone else running this report may see different results.
            </p>
          )}

          {over && (
            <p role="alert" className="flex items-start gap-2 rounded-btn bg-amber-500/10 p-2.5 text-[12.5px]">
              <AlertTriangle aria-hidden className="mt-0.5 size-4 shrink-0" />
              {truncates
                ? `A ${format.toUpperCase()} file holds at most ${limit.toLocaleString()} rows. Only the first ${limit.toLocaleString()} will be included, and the file will say so.`
                : `A ${format.toUpperCase()} file holds at most ${limit.toLocaleString()} rows, and this report matches ${total!.toLocaleString()}. Narrow the filters, or choose another format.`}
            </p>
          )}

          {data.resolvedFilters.length > 0 && (
            <ul className="list-disc space-y-0.5 pl-5 text-[12.5px] text-muted-foreground">
              {data.resolvedFilters.map((f) => (
                <li key={f}>{f}</li>
              ))}
            </ul>
          )}

          {data.sample.length === 0 ? (
            <p className="text-[13.5px] text-muted-foreground">No data found for these filters.</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-[12.5px]">
                <thead>
                  <tr>
                    {data.columns.map((c) => (
                      <th key={c.key} className="border-b border-border px-2 py-1.5 font-semibold whitespace-nowrap">
                        {c.label}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {data.sample.map((row, i) => (
                    <tr key={i}>
                      {row.map((cell, j) => (
                        <td key={j} className="max-w-48 truncate border-b border-border/50 px-2 py-1.5">
                          {cell}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
              <p className="mt-1 text-[12px] text-muted-foreground">First {data.sample.length} rows.</p>
            </div>
          )}
        </div>
      )}
    </aside>
  )
}
