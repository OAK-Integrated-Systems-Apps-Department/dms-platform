"use client"

import { useState } from "react"
import { ArrowDown, ArrowUp, GripVertical, Plus, X } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { Input } from "@/components/ui/input"
import type { CatalogDto } from "@/lib/reports/api"
import { groupFields, move, type ReportDefinition } from "@/lib/reports/definition"

import { SELECT_CLASS } from "./filter-value-input"

interface Props {
  definition: ReportDefinition
  catalog: CatalogDto
  problems: Record<string, string>
  onChange: (definition: ReportDefinition) => void
}

/** Step 2: columns (chosen and reordered), sort keys and the group-by field. */
export function ColumnsStep({ definition, catalog, problems, onChange }: Props) {
  const [search, setSearch] = useState("")
  const [dragging, setDragging] = useState<number | null>(null)
  const byKey = new Map(catalog.fields.map((f) => [f.key, f]))
  const limits = catalog.settings.limits
  const label = (key: string) => byKey.get(key)?.label ?? key

  const setColumns = (columns: string[]) => onChange({ ...definition, columns })
  const toggle = (key: string, on: boolean) => setColumns(on ? [...definition.columns, key] : definition.columns.filter((c) => c !== key))

  const sortable = catalog.fields.filter((f) => f.sortable)
  const groupable = catalog.fields.filter((f) => f.groupable)

  return (
    <div className="space-y-6">
      <div className="grid gap-5 md:grid-cols-2">
        <div>
          <Input aria-label="Search columns" placeholder="Search columns…" value={search} onChange={(e) => setSearch(e.target.value)} />
          <div className="mt-3 max-h-72 space-y-3 overflow-y-auto pr-1">
            {groupFields(catalog.fields.filter((f) => f.selectable), search).map((g) => (
              <div key={g.group}>
                <p className="mb-1 text-[11px] font-semibold tracking-[0.07em] text-muted-foreground uppercase">{g.group}</p>
                {g.fields.map((f) => {
                  const on = definition.columns.includes(f.key)
                  return (
                    <Checkbox
                      key={f.key}
                      label={f.label}
                      checked={on}
                      disabled={!on && definition.columns.length >= limits.maxColumns}
                      onCheckedChange={(checked) => toggle(f.key, checked)}
                    />
                  )
                })}
              </div>
            ))}
          </div>
        </div>

        <div>
          <p className="mb-2 text-[13px] text-muted-foreground">
            Columns in this order ({definition.columns.length} of {limits.maxColumns}). Drag, or use the arrows.
          </p>
          <ol className="space-y-1.5" aria-label="Columns in order">
            {definition.columns.map((key, index) => (
              <li
                key={key}
                draggable
                onDragStart={() => setDragging(index)}
                onDragOver={(e) => e.preventDefault()}
                onDrop={() => {
                  if (dragging !== null) setColumns(move(definition.columns, dragging, index))
                  setDragging(null)
                }}
                className="flex items-center gap-2 rounded-btn border border-border bg-background px-2 py-1.5 text-[13.5px]"
              >
                <GripVertical aria-hidden className="size-4 shrink-0 text-muted-foreground" />
                <span className="min-w-0 flex-1 truncate">{label(key)}</span>
                <Button type="button" variant="ghost" size="icon-xs" aria-label={`Move ${label(key)} up`} disabled={index === 0} onClick={() => setColumns(move(definition.columns, index, index - 1))}>
                  <ArrowUp />
                </Button>
                <Button type="button" variant="ghost" size="icon-xs" aria-label={`Move ${label(key)} down`} disabled={index === definition.columns.length - 1} onClick={() => setColumns(move(definition.columns, index, index + 1))}>
                  <ArrowDown />
                </Button>
                <Button type="button" variant="ghost" size="icon-xs" aria-label={`Remove ${label(key)}`} onClick={() => toggle(key, false)}>
                  <X />
                </Button>
              </li>
            ))}
          </ol>
          {problems.columns && (
            <p role="alert" className="mt-2 text-[12.5px] text-destructive">
              {problems.columns}
            </p>
          )}
        </div>
      </div>

      <div>
        <p className="mb-2 text-[13.5px] font-medium">Sort by</p>
        <div className="space-y-2">
          {definition.sort.map((s, index) => (
            <div key={index} className="flex flex-wrap items-center gap-2">
              <select
                aria-label={`Sort field ${index + 1}`}
                className={SELECT_CLASS}
                value={s.field}
                onChange={(e) => onChange({ ...definition, sort: definition.sort.map((x, i) => (i === index ? { ...x, field: e.target.value } : x)) })}
              >
                {sortable.map((f) => (
                  <option key={f.key} value={f.key}>
                    {f.label}
                  </option>
                ))}
              </select>
              <select
                aria-label={`Sort direction ${index + 1}`}
                className={SELECT_CLASS}
                value={s.direction}
                onChange={(e) => onChange({ ...definition, sort: definition.sort.map((x, i) => (i === index ? { ...x, direction: e.target.value as "asc" | "desc" } : x)) })}
              >
                <option value="asc">Low to high (A to Z, oldest first)</option>
                <option value="desc">High to low (Z to A, newest first)</option>
              </select>
              <Button type="button" variant="ghost" size="icon" aria-label={`Remove sort ${index + 1}`} onClick={() => onChange({ ...definition, sort: definition.sort.filter((_, i) => i !== index) })}>
                <X />
              </Button>
              {problems[`sort.${index}`] && <span role="alert" className="text-[12.5px] text-destructive">{problems[`sort.${index}`]}</span>}
            </div>
          ))}
          {definition.sort.length < limits.maxSortKeys && sortable.length > 0 && (
            <Button
              type="button"
              variant="outline"
              onClick={() => {
                const used = new Set(definition.sort.map((s) => s.field))
                const next = sortable.find((f) => !used.has(f.key)) ?? sortable[0]
                onChange({ ...definition, sort: [...definition.sort, { field: next.key, direction: "asc" }] })
              }}
            >
              <Plus /> Add a sort
            </Button>
          )}
          {problems.sort && <p role="alert" className="text-[12.5px] text-destructive">{problems.sort}</p>}
        </div>
      </div>

      <div>
        <label htmlFor="report-group-by" className="mb-2 block text-[13.5px] font-medium">
          Group by
        </label>
        <select
          id="report-group-by"
          className={SELECT_CLASS}
          value={definition.groupBy ?? ""}
          onChange={(e) => onChange({ ...definition, groupBy: e.target.value || null })}
        >
          <option value="">No grouping</option>
          {groupable.map((f) => (
            <option key={f.key} value={f.key}>
              {f.label}
            </option>
          ))}
        </select>
        {problems.groupBy && <p role="alert" className="mt-1 text-[12.5px] text-destructive">{problems.groupBy}</p>}
      </div>
    </div>
  )
}
