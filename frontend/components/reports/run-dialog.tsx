"use client"

import { useState } from "react"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from "@/components/ui/sheet"
import type { CatalogDto, CatalogField, TemplateDto } from "@/lib/reports/api"
import { OPERATOR_ARITY, operatorLabel, relativeLabel, type ReportFilter } from "@/lib/reports/definition"
import { FORMAT_LABELS } from "@/lib/reports/format"
import { fieldProblems } from "@/lib/reports/messages"
import { useFieldValues, useStartRun } from "@/lib/reports/use-reports"

interface RunDialogProps {
  template: TemplateDto
  catalog: CatalogDto
  onClose: () => void
  /** Called once the run has been accepted, so the page can offer a way to its history. */
  onStarted: () => void
}

/**
 * Run a report: pick the file format and, for a report whose author left some filters open, change just those. Everything else in the
 * report is fixed - the server ignores an override of any other filter.
 */
export function RunDialog({ template, catalog, onClose, onStarted }: RunDialogProps) {
  const start = useStartRun()
  const formats = catalog.settings.allowedFormats
  const [format, setFormat] = useState(formats.includes(catalog.settings.defaultFormat) ? catalog.settings.defaultFormat : (formats[0] ?? "csv"))
  const open = new Set(template.userEditableFilters ?? [])
  const editable = template.definition.filters.filter((f) => open.has(f.field))
  const [values, setValues] = useState<Record<string, string[]>>(() => Object.fromEntries(editable.map((f) => [f.field, f.values])))
  const [problems, setProblems] = useState<Record<string, string>>({})
  const fields = new Map(catalog.fields.map((f) => [f.key, f]))

  async function submit() {
    const filterOverrides: ReportFilter[] = editable.map((f) => ({ field: f.field, operator: f.operator, values: values[f.field] ?? [] }))
    const missing: Record<string, string> = {}
    for (const f of filterOverrides) {
      const arity = OPERATOR_ARITY[f.operator] ?? 1
      const given = f.values.filter((v) => v !== "").length
      if ((arity === -1 && given < 1) || (arity > 0 && given !== arity)) missing[f.field] = "Give a value for this filter."
    }
    setProblems(missing)
    if (Object.keys(missing).length) return
    try {
      await start.mutateAsync({ templateId: template.id, format, filterOverrides: editable.length ? filterOverrides : undefined })
      onStarted()
      onClose()
    } catch (error) {
      setProblems(fieldProblems(error))
    }
  }

  return (
    <Sheet open onOpenChange={(o) => !o && !start.isPending && onClose()}>
      <SheetContent side="right" className="w-full gap-0 data-[side=right]:sm:max-w-[520px]">
        <SheetHeader className="border-b border-border">
          <SheetTitle>{editable.length ? "Run with options" : "Run report"}</SheetTitle>
          <SheetDescription>{template.name}. The file contains only the documents you have access to.</SheetDescription>
        </SheetHeader>
        <div className="flex-1 space-y-5 overflow-y-auto p-5">
          <fieldset>
            <legend className="mb-1.5 text-[12.5px] font-medium">File format</legend>
            <div className="flex gap-3">
              {formats.map((f) => (
                <label key={f} className="flex items-center gap-1.5 text-[13px]">
                  <input type="radio" name="run-format" checked={format === f} onChange={() => setFormat(f)} />
                  {FORMAT_LABELS[f] ?? f.toUpperCase()}
                </label>
              ))}
            </div>
          </fieldset>

          {editable.length > 0 && (
            <div className="space-y-4">
              <p className="text-[12.5px] text-muted-foreground">The author of this report lets you change these filters:</p>
              {editable.map((f) => (
                <FilterOverride
                  key={f.field}
                  filter={f}
                  field={fields.get(f.field)}
                  value={values[f.field] ?? []}
                  relativeOptions={catalog.relativeDateOptions}
                  error={problems[f.field]}
                  onChange={(v) => setValues({ ...values, [f.field]: v })}
                />
              ))}
            </div>
          )}
          {problems.format && (
            <p role="alert" className="text-[12.5px] text-destructive">
              {problems.format}
            </p>
          )}
        </div>
        <div className="flex justify-end gap-2 border-t border-border p-4">
          <Button type="button" variant="outline" onClick={onClose} disabled={start.isPending}>
            Cancel
          </Button>
          <Button type="button" onClick={() => void submit()} disabled={start.isPending || formats.length === 0}>
            {start.isPending ? "Starting…" : "Run report"}
          </Button>
        </div>
      </SheetContent>
    </Sheet>
  )
}

function FilterOverride({
  filter,
  field,
  value,
  relativeOptions,
  error,
  onChange,
}: {
  filter: ReportFilter
  field: CatalogField | undefined
  value: string[]
  relativeOptions: string[]
  error?: string
  onChange: (v: string[]) => void
}) {
  const label = field?.label ?? filter.field
  const arity = OPERATOR_ARITY[filter.operator] ?? 1
  const id = `override-${filter.field}`
  return (
    <div>
      <label htmlFor={id} className="mb-1 block text-[13px] font-medium">
        {label} <span className="font-normal text-muted-foreground">{operatorLabel(filter.operator)}</span>
      </label>
      {arity === 0 ? (
        <p className="text-[12.5px] text-muted-foreground">Nothing to choose for this filter.</p>
      ) : filter.operator === "relative" ? (
        <select
          id={id}
          value={value[0] ?? ""}
          onChange={(e) => onChange([e.target.value])}
          className="h-10 rounded-btn border border-border bg-background px-2 text-[13px]"
        >
          {relativeOptions.map((o) => (
            <option key={o} value={o}>
              {relativeLabel(o)}
            </option>
          ))}
        </select>
      ) : field && (field.type === "choice" || field.type === "reference") ? (
        <ChoicePicker fieldKey={field.key} id={id} value={value} onChange={onChange} />
      ) : (
        <div className="flex gap-2">
          {Array.from({ length: Math.max(1, arity === -1 ? 1 : arity) }).map((_, i) => (
            <Input
              key={i}
              id={i === 0 ? id : undefined}
              aria-label={`${label} ${i + 1}`}
              type={field?.type === "date" ? "date" : field?.type === "number" ? "number" : "text"}
              value={value[i] ?? ""}
              onChange={(e) => {
                const next = value.slice()
                next[i] = e.target.value
                onChange(next)
              }}
            />
          ))}
        </div>
      )}
      {error && (
        <p role="alert" className="mt-1 text-[12.5px] text-destructive">
          {error}
        </p>
      )}
    </div>
  )
}

/** Pick one or more choices from the server's paged list (type to narrow it). */
function ChoicePicker({ fieldKey, id, value, onChange }: { fieldKey: string; id: string; value: string[]; onChange: (v: string[]) => void }) {
  const [search, setSearch] = useState("")
  const { data, isPending } = useFieldValues(fieldKey, search, 0)
  const toggle = (v: string) => onChange(value.includes(v) ? value.filter((x) => x !== v) : [...value, v])
  return (
    <div className="space-y-2">
      <Input id={id} type="search" placeholder="Search…" value={search} onChange={(e) => setSearch(e.target.value)} />
      <ul className="max-h-40 overflow-y-auto rounded-md border border-border">
        {isPending && <li className="px-3 py-2 text-[12.5px] text-muted-foreground">Loading…</li>}
        {data?.content.length === 0 && <li className="px-3 py-2 text-[12.5px] text-muted-foreground">No matches.</li>}
        {data?.content.map((o) => (
          <li key={o.value}>
            <label className="flex items-center gap-2 px-3 py-1.5 text-[13px] hover:bg-muted">
              <input type="checkbox" checked={value.includes(o.value)} onChange={() => toggle(o.value)} />
              {o.label}
            </label>
          </li>
        ))}
      </ul>
      {data?.hasMore && <p className="text-[12px] text-muted-foreground">More choices match. Type to narrow the list.</p>}
    </div>
  )
}
