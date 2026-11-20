"use client"

import { useState } from "react"
import { Plus, X } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Switch } from "@/components/ui/switch"
import type { CatalogDto, CatalogField } from "@/lib/reports/api"
import { OPERATOR_ARITY, groupFields, operatorLabel, type ReportDefinition, type ReportFilter } from "@/lib/reports/definition"

import { FilterValueInput, SELECT_CLASS } from "./filter-value-input"

interface Props {
  definition: ReportDefinition
  catalog: CatalogDto
  problems: Record<string, string>
  onChange: (definition: ReportDefinition) => void
  onLabel: (key: string, label: string) => void
  /** When set, only filters on these fields may be edited or removed, and none added (a locked template's options). */
  editableFields?: string[]
}

function blankValues(operator: string): string[] {
  const arity = OPERATOR_ARITY[operator] ?? 1
  return arity === 2 ? ["", ""] : arity === 0 ? [] : [""]
}

/** Step 1: which documents the report covers. */
export function FiltersStep({ definition, catalog, problems, onChange, onLabel, editableFields }: Props) {
  const [picking, setPicking] = useState(false)
  const [search, setSearch] = useState("")
  const byKey = new Map(catalog.fields.map((f) => [f.key, f]))
  const restricted = editableFields !== undefined
  const canAdd = !restricted && definition.filters.length < catalog.settings.limits.maxFilters

  const update = (index: number, next: ReportFilter) =>
    onChange({ ...definition, filters: definition.filters.map((f, i) => (i === index ? next : f)) })
  const remove = (index: number) => onChange({ ...definition, filters: definition.filters.filter((_, i) => i !== index) })
  const add = (field: CatalogField) => {
    const operator = field.operators[0] ?? "equals"
    onChange({ ...definition, filters: [...definition.filters, { field: field.key, operator, values: blankValues(operator) }] })
    setPicking(false)
    setSearch("")
  }

  const groups = groupFields(
    catalog.fields.filter((f) => f.filterable),
    search
  )

  return (
    <div className="space-y-3">
      {definition.filters.length === 0 && (
        <p className="text-[13.5px] text-muted-foreground">No filters: the report covers every document you can see.</p>
      )}
      {definition.filters.map((filter, index) => {
        const field = byKey.get(filter.field)
        const editable = !restricted || editableFields.includes(filter.field)
        const problem = problems[`filters.${index}`]
        if (!field) {
          return (
            <div key={index} className="rounded-btn border border-destructive/40 p-3 text-[13px] text-destructive">
              {filter.field} is no longer available.
              {editable && (
                <Button type="button" variant="ghost" size="xs" onClick={() => remove(index)}>
                  Remove
                </Button>
              )}
            </div>
          )
        }
        return (
          <div key={index} className="rounded-btn border border-border p-3" data-testid="filter-row">
            <div className="flex flex-wrap items-center gap-2">
              <span className="min-w-28 text-[13.5px] font-medium">{field.label}</span>
              <select
                aria-label={`${field.label} comparison`}
                className={SELECT_CLASS}
                disabled={!editable}
                value={filter.operator}
                onChange={(e) => update(index, { ...filter, operator: e.target.value, values: blankValues(e.target.value) })}
              >
                {field.operators.map((op) => (
                  <option key={op} value={op}>
                    {operatorLabel(op)}
                  </option>
                ))}
              </select>
              <FilterValueInput
                field={field}
                filter={filter}
                catalog={catalog}
                disabled={!editable}
                onLabel={(value, label) => onLabel(`${field.key}:${value}`, label)}
                onChange={(values) => update(index, { ...filter, values })}
              />
              {editable && !restricted && (
                <Button type="button" variant="ghost" size="icon" aria-label={`Remove the ${field.label} filter`} onClick={() => remove(index)}>
                  <X />
                </Button>
              )}
            </div>
            {problem && (
              <p role="alert" className="mt-2 text-[12.5px] text-destructive">
                {problem}
              </p>
            )}
          </div>
        )
      })}
      {problems.filters && (
        <p role="alert" className="text-[12.5px] text-destructive">
          {problems.filters}
        </p>
      )}

      {canAdd && (
        <div>
          {!picking ? (
            <Button type="button" variant="outline" onClick={() => setPicking(true)}>
              <Plus /> Add a filter
            </Button>
          ) : (
            <div className="rounded-btn border border-border p-3">
              <Input aria-label="Search fields" placeholder="Search fields…" autoFocus value={search} onChange={(e) => setSearch(e.target.value)} />
              <div className="mt-2 max-h-56 overflow-y-auto">
                {groups.length === 0 && <p className="py-2 text-[13px] text-muted-foreground">No field matches.</p>}
                {groups.map((g) => (
                  <div key={g.group} className="mb-2">
                    <p className="px-1 text-[11px] font-semibold tracking-[0.07em] text-muted-foreground uppercase">{g.group}</p>
                    {g.fields.map((f) => (
                      <button type="button" key={f.key} className="block w-full rounded px-2 py-1.5 text-left text-[13.5px] hover:bg-muted" onClick={() => add(f)}>
                        {f.label}
                      </button>
                    ))}
                  </div>
                ))}
              </div>
              <Button type="button" variant="ghost" size="xs" onClick={() => setPicking(false)}>
                Cancel
              </Button>
            </div>
          )}
        </div>
      )}

      <label className="flex items-center gap-3 pt-2 text-[13.5px]">
        <Switch
          aria-label="Include inactive and archived documents"
          disabled={restricted}
          checked={Boolean(definition.includeInactive)}
          onCheckedChange={(checked) => onChange({ ...definition, includeInactive: checked })}
        />
        Include inactive and archived documents
      </label>
    </div>
  )
}
