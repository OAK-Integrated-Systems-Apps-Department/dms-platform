"use client"

import { DateTimePicker } from "@/components/ui/datetime-picker"
import { SearchInput } from "@/components/ui/search-input"
import { SearchableSelect } from "@/components/ui/searchable-select"
import { QUARANTINE_STATUS_LABELS } from "@/lib/quarantine/quarantine-badges"

import type { QuarantineStatus } from "@/lib/quarantine/api"

/**
 * `detectedFrom`/`detectedTo` are raw DateTimePicker values
 * ("YYYY-MM-DDTHH:mm"); the list page appends ":00Z" before sending them,
 * the same way the Audit Log does (the backend binds an OffsetDateTime).
 */
export interface QuarantineFilterState {
  search: string
  status: "" | QuarantineStatus
  detectedFrom: string
  detectedTo: string
}

export const EMPTY_QUARANTINE_FILTERS: QuarantineFilterState = {
  search: "",
  status: "",
  detectedFrom: "",
  detectedTo: "",
}

const STATUS_OPTIONS = (Object.keys(QUARANTINE_STATUS_LABELS) as QuarantineStatus[]).map((status) => ({
  value: status,
  label: QUARANTINE_STATUS_LABELS[status],
}))

interface QuarantineFiltersProps {
  filters: QuarantineFilterState
  onChange: (update: Partial<QuarantineFilterState>) => void
}

/** Search (filename or threat) + status + detection date range - few enough to show inline, unlike the audit log's panel. */
export function QuarantineFilters({ filters, onChange }: QuarantineFiltersProps) {
  const hasActive = Boolean(filters.search || filters.status || filters.detectedFrom || filters.detectedTo)
  return (
    <div className="mb-4 flex flex-wrap items-center gap-2.5">
      <SearchInput
        value={filters.search}
        onChange={(search) => onChange({ search })}
        placeholder="Search filename or threat…"
        className="w-[280px]"
        aria-label="Search quarantined files"
      />
      <SearchableSelect
        allLabel="All statuses"
        searchable={false}
        selected={filters.status}
        onChange={(status) => onChange({ status: status as QuarantineFilterState["status"] })}
        options={STATUS_OPTIONS}
        className="w-[170px]"
      />
      <DateTimePicker
        value={filters.detectedFrom}
        onChange={(detectedFrom) => onChange({ detectedFrom })}
        placeholder="Detected from"
        className="w-[210px]"
      />
      <DateTimePicker
        value={filters.detectedTo}
        onChange={(detectedTo) => onChange({ detectedTo })}
        placeholder="Detected to"
        className="w-[210px]"
      />
      {hasActive && (
        <button
          type="button"
          onClick={() => onChange(EMPTY_QUARANTINE_FILTERS)}
          className="text-[12.5px] font-semibold text-muted-foreground hover:text-foreground"
        >
          Clear all
        </button>
      )}
    </div>
  )
}
