"use client"

import { MultiSelect } from "@/components/ui/multi-select"

export interface ActorOption {
  id: string
  label: string
}

interface ActorFilterSelectProps {
  options: ActorOption[]
  /** Selected actors' ids - an empty array means "All actors" (no filter). */
  selected: string[]
  onChange: (actorIds: string[]) => void
  className?: string
}

/**
 * Multi-select searchable actor filter for the Audit Log toolbar - a thin
 * wrapper over `MultiSelect` (components/ui/multi-select.tsx), mapping
 * `{id, label}` actor options onto that component's plain `{value, label}`
 * shape, so the checkbox multi-select and search behavior only need to be
 * implemented and fixed in one place.
 */
export function ActorFilterSelect({ options, selected, onChange, className }: ActorFilterSelectProps) {
  return (
    <MultiSelect
      className={className}
      allLabel="All actors"
      searchPlaceholder="Search actors…"
      selected={selected}
      onChange={onChange}
      options={options.map((option) => ({ value: option.id, label: option.label }))}
    />
  )
}
