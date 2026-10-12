import type { BulkImportSummaryDto } from "@/lib/bulk-imports/api"

import type { BulkExportFiltersDto } from "./api"

/**
 * The Export screen's filter bar. Dates are raw `<input type="date">`
 * values ("YYYY-MM-DD", local days); `titleContains` is applied debounced
 * by the page, not here.
 */
export interface ExportFilterState {
  documentTypeId: string
  createdFrom: string
  createdTo: string
  onlyMine: boolean
  titleContains: string
  importBatchId: string
}

export const EMPTY_EXPORT_FILTERS: ExportFilterState = {
  documentTypeId: "",
  createdFrom: "",
  createdTo: "",
  onlyMine: false,
  titleContains: "",
  importBatchId: "",
}

/** Local midnight of a "YYYY-MM-DD" day, `addDays` later, as an ISO date-time with this browser's offset. */
function localMidnight(day: string, addDays = 0): string {
  const [year, month, date] = day.split("-").map(Number)
  const value = new Date(year, month - 1, date + addDays)
  const pad = (n: number) => String(Math.abs(n)).padStart(2, "0")
  const offset = -value.getTimezoneOffset()
  const sign = offset >= 0 ? "+" : "-"
  return (
    `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(value.getDate())}T00:00:00` +
    `${sign}${pad(Math.trunc(offset / 60))}:${pad(offset % 60)}`
  )
}

/**
 * Filter-bar state as the backend's BulkExportFilters. The backend's
 * `createdTo` is exclusive, so "to" is sent as the next day's midnight -
 * the chosen day itself is included.
 */
export function toApiFilters(filters: ExportFilterState, currentUserId: string | undefined): BulkExportFiltersDto {
  const title = filters.titleContains.trim()
  return {
    documentTypeId: filters.documentTypeId || undefined,
    createdFrom: filters.createdFrom ? localMidnight(filters.createdFrom) : undefined,
    createdTo: filters.createdTo ? localMidnight(filters.createdTo, 1) : undefined,
    uploadedBy: filters.onlyMine ? currentUserId : undefined,
    titleContains: title || undefined,
    importBatchId: filters.importBatchId || undefined,
  }
}

export function hasActiveFilters(filters: ExportFilterState): boolean {
  return Boolean(
    filters.documentTypeId ||
      filters.createdFrom ||
      filters.createdTo ||
      filters.onlyMine ||
      filters.titleContains ||
      filters.importBatchId
  )
}

/**
 * Whether an import is worth offering in the import-batch filter: one that
 * finished and created at least one document. Uploading, queued, running,
 * cancelled (by the user or expired), failed and zero-success imports
 * would only ever match nothing.
 */
export function isExportableImportBatch(batch: BulkImportSummaryDto): boolean {
  return (batch.status === "completed" || batch.status === "completed_with_errors") && batch.successCount > 0
}
