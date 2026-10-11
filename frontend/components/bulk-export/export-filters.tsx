"use client"

import { SearchInput } from "@/components/ui/search-input"
import { SearchableSelect, type SearchableSelectOption } from "@/components/ui/searchable-select"
import { Toggle } from "@/components/ui/toggle"
import { EMPTY_EXPORT_FILTERS, hasActiveFilters, type ExportFilterState } from "@/lib/bulk-exports/filters"

interface ExportFiltersProps {
  filters: ExportFilterState
  onChange: (update: Partial<ExportFilterState>) => void
  documentTypeOptions: SearchableSelectOption[]
  /** Null hides the import-batch filter (the caller can't list their imports). */
  importBatchOptions: SearchableSelectOption[] | null
}

const DATE_INPUT =
  "h-[38px] rounded-btn border border-border bg-muted px-3 text-[13px] text-foreground outline-none focus-visible:border-primary focus-visible:bg-card"

/**
 * Type, created date range, title contains, only my uploads and - for a
 * caller who can import - one of their import batches. There is no
 * uploader picker: the user directory needs user-management permissions
 * an exporter may not hold.
 */
export function ExportFilters({ filters, onChange, documentTypeOptions, importBatchOptions }: ExportFiltersProps) {
  return (
    <div className="mb-4 flex flex-wrap items-center gap-2.5">
      <SearchInput
        value={filters.titleContains}
        onChange={(titleContains) => onChange({ titleContains })}
        placeholder="Title contains…"
        className="w-[240px]"
        aria-label="Title contains"
      />
      <SearchableSelect
        allLabel="All document types"
        searchPlaceholder="Search types…"
        selected={filters.documentTypeId}
        onChange={(documentTypeId) => onChange({ documentTypeId })}
        options={documentTypeOptions}
        className="w-[200px]"
      />
      <label className="flex items-center gap-1.5 text-[12.5px] text-muted-foreground">
        From
        <input
          type="date"
          value={filters.createdFrom}
          max={filters.createdTo || undefined}
          onChange={(event) => onChange({ createdFrom: event.target.value })}
          aria-label="Created from"
          className={DATE_INPUT}
        />
      </label>
      <label className="flex items-center gap-1.5 text-[12.5px] text-muted-foreground">
        To
        <input
          type="date"
          value={filters.createdTo}
          min={filters.createdFrom || undefined}
          onChange={(event) => onChange({ createdTo: event.target.value })}
          aria-label="Created to"
          className={DATE_INPUT}
        />
      </label>
      {importBatchOptions && (
        <SearchableSelect
          allLabel="Any import"
          searchPlaceholder="Search imports…"
          selected={filters.importBatchId}
          onChange={(importBatchId) => onChange({ importBatchId })}
          options={importBatchOptions}
          className="w-[260px]"
        />
      )}
      <span className="flex items-center gap-2 text-[12.5px] text-foreground">
        <Toggle checked={filters.onlyMine} onChange={(onlyMine) => onChange({ onlyMine })} label="Only my uploads" />
        Only my uploads
      </span>
      {hasActiveFilters(filters) && (
        <button
          type="button"
          onClick={() => onChange(EMPTY_EXPORT_FILTERS)}
          className="text-[12.5px] font-semibold text-muted-foreground hover:text-foreground"
        >
          Clear all
        </button>
      )}
    </div>
  )
}
