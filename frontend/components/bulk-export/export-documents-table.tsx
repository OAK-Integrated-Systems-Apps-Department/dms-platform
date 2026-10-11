"use client"

import type { ExportableDocumentDto } from "@/lib/bulk-exports/api"
import { formatBytes } from "@/lib/quarantine/quarantine-badges"
import { cn, formatDateTime } from "@/lib/utils"

import { RowCheckbox } from "./row-checkbox"

interface ExportDocumentsTableProps {
  rows: ExportableDocumentDto[]
  isSelected: (documentId: string) => boolean
  onToggle: (row: ExportableDocumentDto, checked: boolean) => void
  onTogglePage: (checked: boolean) => void
  /** Every matching document is selected by filter - the boxes show it but can't be changed one by one. */
  selectingAllMatching: boolean
}

const TH = "px-3 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase"

/** The documents matching the filters, newest first (server-sorted), with a selection column. */
export function ExportDocumentsTable({
  rows,
  isSelected,
  onToggle,
  onTogglePage,
  selectingAllMatching,
}: ExportDocumentsTableProps) {
  const selectedOnPage = rows.filter((row) => isSelected(row.documentId)).length
  const allOnPage = rows.length > 0 && selectedOnPage === rows.length

  return (
    <div className="overflow-hidden rounded-lg border border-border bg-card shadow-[var(--ledger-shadow)]">
      <div className="overflow-x-auto">
        <table className="w-full min-w-[820px] border-collapse">
          <thead>
            <tr className="border-b border-border">
              <th className={cn(TH, "w-10")}>
                <RowCheckbox
                  checked={allOnPage}
                  indeterminate={selectedOnPage > 0 && !allOnPage}
                  onChange={onTogglePage}
                  disabled={rows.length === 0 || selectingAllMatching}
                  label="Select all on this page"
                />
              </th>
              <th className={TH}>Title</th>
              <th className={TH}>Type</th>
              <th className={TH}>Uploaded By</th>
              <th className={TH}>Created</th>
              <th className={cn(TH, "text-right")}>Size</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && (
              <tr>
                <td colSpan={6} className="px-3 py-10 text-center text-[13px] text-muted-foreground">
                  No documents match your filters.
                </td>
              </tr>
            )}
            {rows.map((row) => {
              const selected = isSelected(row.documentId)
              return (
                <tr
                  key={row.documentId}
                  onClick={selectingAllMatching ? undefined : () => onToggle(row, !selected)}
                  className={cn(
                    "border-b border-border last:border-b-0",
                    !selectingAllMatching && "cursor-pointer hover:bg-muted",
                    selected && "bg-primary/5"
                  )}
                >
                  <td className="px-3 py-3">
                    <RowCheckbox
                      checked={selected}
                      onChange={(checked) => onToggle(row, checked)}
                      disabled={selectingAllMatching}
                      label={`Select ${row.title}`}
                    />
                  </td>
                  <td className="px-3 py-3 text-[13.5px] font-semibold text-foreground">{row.title}</td>
                  <td className="px-3 py-3 text-[13px] text-foreground">{row.documentTypeName ?? "—"}</td>
                  <td className="px-3 py-3 text-[13px] text-foreground">{row.uploadedByName ?? "—"}</td>
                  <td className="px-3 py-3 font-mono text-[12.5px] text-muted-foreground">
                    {formatDateTime(row.createdAt)}
                  </td>
                  <td className="px-3 py-3 text-right text-[13px] text-muted-foreground">
                    {row.sizeBytes == null ? "—" : formatBytes(row.sizeBytes)}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </div>
  )
}
