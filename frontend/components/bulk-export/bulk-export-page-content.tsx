"use client"

import { useMemo, useState } from "react"

import { BackendUnavailable } from "@/components/ui/backend-unavailable"
import { LoadingState } from "@/components/ui/loading-state"
import { TablePagination } from "@/components/ui/table-pagination"
import { isNetworkError } from "@/lib/api-error"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { useCurrentUser } from "@/lib/auth/use-current-user"
import { usePermissions } from "@/lib/auth/use-permissions"
import { exportErrorMessage, type BulkExportSelectionDto, type ExportableDocumentDto } from "@/lib/bulk-exports/api"
import {
  EMPTY_EXPORT_FILTERS,
  isExportableImportBatch,
  toApiFilters,
  type ExportFilterState,
} from "@/lib/bulk-exports/filters"
import {
  useExportDocumentTypes,
  useExportableDocuments,
  useImportBatchOptions,
} from "@/lib/bulk-exports/use-bulk-exports"
import { useDebouncedValue } from "@/lib/bulk-exports/use-debounced-value"
import { formatDateTime } from "@/lib/utils"

import { ExportActionBar } from "./export-action-bar"
import { ExportDocumentsTable } from "./export-documents-table"
import { ExportFilters } from "./export-filters"
import { MyExports } from "./my-exports"
import { SelectionBanner } from "./selection-banner"

const DEFAULT_PAGE_SIZE = 25
/** The backend caps the documents page at 50. */
const PAGE_SIZE_OPTIONS = [10, 25, 50]
const TITLE_DEBOUNCE_MS = 350

/** Ticked documents by id, or "everything matching the filters" - sent as filters, not ids. */
type Selection = { mode: "ids"; ids: ReadonlySet<string> } | { mode: "filters" }

const NOTHING_SELECTED: Selection = { mode: "ids", ids: new Set() }

/**
 * Filters, the matching documents with a selection column, the action bar
 * and the caller's exports. Changing any filter (the title once debounced)
 * clears the selection and goes back to page 1, so what's selected is
 * always something the user can see the filters for.
 */
export function BulkExportPageContent() {
  const { hasPermission } = usePermissions()
  const { data: currentUser } = useCurrentUser()
  const canListImports = hasPermission(PermissionCodes.FIE_BULK_IMPORT_UPLOAD_FILE)

  const [filters, setFilters] = useState<ExportFilterState>(EMPTY_EXPORT_FILTERS)
  const debouncedTitle = useDebouncedValue(filters.titleContains, TITLE_DEBOUNCE_MS)
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE)
  const [selection, setSelection] = useState<Selection>(NOTHING_SELECTED)

  const apiFilters = useMemo(
    () => toApiFilters({ ...filters, titleContains: debouncedTitle }, currentUser?.userId),
    [filters, debouncedTitle, currentUser?.userId]
  )

  // Reset paging and selection whenever the applied filters change - during
  // render rather than in an effect, so the stale selection never shows.
  const filterKey = JSON.stringify(apiFilters)
  const [appliedKey, setAppliedKey] = useState(filterKey)
  if (appliedKey !== filterKey) {
    setAppliedKey(filterKey)
    setPage(1)
    setSelection(NOTHING_SELECTED)
  }

  const { documentTypes } = useExportDocumentTypes()
  const { batches } = useImportBatchOptions(canListImports)
  const documentsQuery = useExportableDocuments(apiFilters, page, pageSize)

  const documentTypeOptions = useMemo(
    () => documentTypes.map((type) => ({ value: type.documentTypeId, label: type.name })),
    [documentTypes]
  )
  const importBatchOptions = useMemo(
    () =>
      canListImports
        ? batches.filter(isExportableImportBatch).map((batch) => ({
            value: batch.batchId,
            label: `${batch.documentTypeName ?? "Unknown type"} · ${formatDateTime(batch.createdAt)} · ${batch.successCount} file${batch.successCount === 1 ? "" : "s"}`,
          }))
        : null,
    [canListImports, batches]
  )

  const rows = documentsQuery.data?.content ?? []
  const totalMatching = documentsQuery.data?.totalElements ?? 0
  const selectingAllMatching = selection.mode === "filters"
  const isSelected = (id: string) => selection.mode === "filters" || selection.ids.has(id)
  const allOnPageSelected = rows.length > 0 && rows.every((row) => isSelected(row.documentId))
  const selectedCount = selection.mode === "filters" ? totalMatching : selection.ids.size

  function updateIds(change: (ids: Set<string>) => void) {
    setSelection((current) => {
      const ids = new Set(current.mode === "ids" ? current.ids : [])
      change(ids)
      return { mode: "ids", ids }
    })
  }

  function handleToggle(row: ExportableDocumentDto, checked: boolean) {
    updateIds((ids) => (checked ? ids.add(row.documentId) : ids.delete(row.documentId)))
  }

  function handleTogglePage(checked: boolean) {
    updateIds((ids) => rows.forEach((row) => (checked ? ids.add(row.documentId) : ids.delete(row.documentId))))
  }

  function handlePageSizeChange(size: number) {
    setPageSize(size)
    setPage(1)
  }

  const exportSelection: BulkExportSelectionDto | null =
    selection.mode === "filters"
      ? totalMatching > 0
        ? { filters: apiFilters }
        : null
      : selection.ids.size > 0
        ? { documentIds: [...selection.ids] }
        : null

  return (
    <>
      <ExportFilters
        filters={filters}
        onChange={(update) => setFilters((current) => ({ ...current, ...update }))}
        documentTypeOptions={documentTypeOptions}
        importBatchOptions={importBatchOptions}
      />

      {documentsQuery.isPending ? (
        <LoadingState message="Loading documents…" />
      ) : documentsQuery.isError ? (
        isNetworkError(documentsQuery.error) ? (
          <BackendUnavailable onRetry={documentsQuery.refetch} />
        ) : (
          <div className="rounded-lg border border-[var(--rust)] bg-[var(--rust-tint)] p-[26px] text-[13.5px] text-[var(--rust)] shadow-[var(--ledger-shadow)]">
            {exportErrorMessage(documentsQuery.error)}
          </div>
        )
      ) : (
        <>
          <SelectionBanner
            pageCount={rows.length}
            totalMatching={totalMatching}
            allOnPageSelected={allOnPageSelected}
            selectingAllMatching={selectingAllMatching}
            onSelectAllMatching={() => setSelection({ mode: "filters" })}
            onClear={() => setSelection(NOTHING_SELECTED)}
          />
          <ExportDocumentsTable
            rows={rows}
            isSelected={isSelected}
            onToggle={handleToggle}
            onTogglePage={handleTogglePage}
            selectingAllMatching={selectingAllMatching}
          />
          <TablePagination
            currentPage={page}
            totalPages={documentsQuery.data?.totalPages ?? 1}
            onPageChange={setPage}
            pageSize={pageSize}
            pageSizeOptions={PAGE_SIZE_OPTIONS}
            onPageSizeChange={handlePageSizeChange}
          />
          <ExportActionBar
            selectedCount={selectedCount}
            selection={exportSelection}
            onRequested={() => setSelection(NOTHING_SELECTED)}
          />
        </>
      )}

      <MyExports />
    </>
  )
}
