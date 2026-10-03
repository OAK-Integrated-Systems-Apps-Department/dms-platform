import type { ImportRowFailureDto, ImportRowProblemDto } from "@/lib/bulk-imports/api"

const COLUMNS = ["Row", "File", "Problem"]

/**
 * Import rows with their problems, one problem per line - the failed rows of
 * a finished import, or the problem rows of a template being checked.
 * Scrolls inside a fixed-height area (header stays put) so a 500-row batch
 * doesn't push the rest of the card off screen.
 */
export function ImportRowsTable({ rows }: { rows: ImportRowFailureDto[] }) {
  return (
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
          {rows.map((row) => (
            <ImportRow key={row.rowNumber} row={row} />
          ))}
        </tbody>
      </table>
    </div>
  )
}

function ImportRow({ row }: { row: ImportRowFailureDto }) {
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
