"use client"

import { useState } from "react"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import type { CatalogDto, CatalogField } from "@/lib/reports/api"
import { OPERATOR_ARITY, relativeLabel, type ReportFilter } from "@/lib/reports/definition"
import { useFieldValues } from "@/lib/reports/use-reports"

export const SELECT_CLASS =
  "h-[38px] rounded-btn border border-border bg-background px-3 text-[13.5px] outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:opacity-50"

interface Props {
  field: CatalogField
  filter: ReportFilter
  catalog: CatalogDto
  disabled?: boolean
  /** Remembers a chosen value's readable name for the summary sentence. */
  onLabel: (value: string, label: string) => void
  onChange: (values: string[]) => void
}

/** The value control(s) for one filter, chosen by the field's type and the operator's arity. */
export function FilterValueInput({ field, filter, catalog, disabled, onLabel, onChange }: Props) {
  const arity = OPERATOR_ARITY[filter.operator] ?? 1
  const name = `${field.label} value`
  if (arity === 0) return null

  if (filter.operator === "relative") {
    return (
      <select aria-label={name} className={SELECT_CLASS} disabled={disabled} value={filter.values[0] ?? ""} onChange={(e) => onChange([e.target.value])}>
        <option value="">Choose a period…</option>
        {catalog.relativeDateOptions.map((o) => (
          <option key={o} value={o}>
            {relativeLabel(o)}
          </option>
        ))}
      </select>
    )
  }

  if (field.type === "choice" || field.type === "reference") {
    return <ChoicePicker field={field} multi={arity === -1} values={filter.values} disabled={disabled} onLabel={onLabel} onChange={onChange} />
  }

  if (arity === 2) {
    const type = field.type === "date" ? "date" : field.type === "number" ? "number" : "text"
    return (
      <div className="flex flex-wrap items-center gap-2">
        <Input aria-label={`${field.label} from`} type={type} disabled={disabled} value={filter.values[0] ?? ""} onChange={(e) => onChange([e.target.value, filter.values[1] ?? ""])} className="w-40" />
        <span className="text-[13px] text-muted-foreground">and</span>
        <Input aria-label={`${field.label} to`} type={type} disabled={disabled} value={filter.values[1] ?? ""} onChange={(e) => onChange([filter.values[0] ?? "", e.target.value])} className="w-40" />
      </div>
    )
  }

  if (arity === -1) {
    return (
      <Input
        aria-label={`${name} (separate several with commas)`}
        placeholder="Separate several with commas"
        disabled={disabled}
        value={filter.values.join(", ")}
        onChange={(e) => onChange(e.target.value.split(",").map((v) => v.trimStart()))}
        className="w-72"
      />
    )
  }

  const type = field.type === "date" ? "date" : field.type === "number" ? "number" : "text"
  return <Input aria-label={name} type={type} disabled={disabled} value={filter.values[0] ?? ""} onChange={(e) => onChange([e.target.value])} className="w-64" />
}

/** Pick from the field's real choices: searchable and paged, so thousands of people or types stay usable. */
function ChoicePicker({
  field, multi, values, disabled, onLabel, onChange,
}: {
  field: CatalogField
  multi: boolean
  values: string[]
  disabled?: boolean
  onLabel: (value: string, label: string) => void
  onChange: (values: string[]) => void
}) {
  const [search, setSearch] = useState("")
  const [page, setPage] = useState(0)
  const { data, isLoading, isError, refetch } = useFieldValues(field.key, search, page)

  const toggle = (value: string, label: string) => {
    onLabel(value, label)
    if (!multi) return onChange([value])
    onChange(values.includes(value) ? values.filter((v) => v !== value) : [...values, value])
  }

  return (
    <div className="w-72 rounded-btn border border-border p-2">
      <Input
        aria-label={`Search ${field.label}`}
        placeholder={`Search ${field.label.toLowerCase()}…`}
        value={search}
        disabled={disabled}
        onChange={(e) => {
          setSearch(e.target.value)
          setPage(0)
        }}
      />
      <div className="mt-2 max-h-40 overflow-y-auto" role="listbox" aria-label={`${field.label} choices`} aria-multiselectable={multi}>
        {isLoading && <p className="px-1 py-2 text-[12.5px] text-muted-foreground">Loading…</p>}
        {isError && (
          <p className="px-1 py-2 text-[12.5px] text-destructive">
            Could not load the choices.{" "}
            <button type="button" className="underline" onClick={() => void refetch()}>
              Retry
            </button>
          </p>
        )}
        {data && data.content.length === 0 && <p className="px-1 py-2 text-[12.5px] text-muted-foreground">Nothing matches.</p>}
        {data?.content.map((choice) => {
          const selected = values.includes(choice.value)
          return (
            <button
              type="button"
              role="option"
              aria-selected={selected}
              key={choice.value}
              disabled={disabled}
              onClick={() => toggle(choice.value, choice.label)}
              className={`flex w-full items-center rounded px-2 py-1.5 text-left text-[13px] hover:bg-muted ${selected ? "bg-primary/10 font-medium" : ""}`}
            >
              {choice.label}
            </button>
          )
        })}
      </div>
      {data && (page > 0 || data.hasMore) && (
        <div className="mt-1 flex justify-between">
          <Button type="button" variant="ghost" size="xs" disabled={page === 0} onClick={() => setPage(page - 1)}>
            Previous
          </Button>
          <Button type="button" variant="ghost" size="xs" disabled={!data.hasMore} onClick={() => setPage(page + 1)}>
            Next
          </Button>
        </div>
      )}
      {multi && values.length > 0 && <p className="mt-1 text-[12px] text-muted-foreground">{values.length} chosen</p>}
    </div>
  )
}
