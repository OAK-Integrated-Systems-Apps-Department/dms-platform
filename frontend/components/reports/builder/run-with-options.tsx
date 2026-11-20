"use client"

import { useState } from "react"
import { useRouter } from "next/navigation"
import { Lock, Play } from "lucide-react"
import { toast } from "sonner"

import { Button } from "@/components/ui/button"
import type { CatalogDto, TemplateDto } from "@/lib/reports/api"
import { summarise, validateDefinition } from "@/lib/reports/definition"
import { useStartRun } from "@/lib/reports/use-reports"

import { FiltersStep } from "./filters-step"
import { SELECT_CLASS } from "./filter-value-input"
import { PreviewPanel } from "./preview-panel"

interface Props {
  template: TemplateDto
  catalog: CatalogDto
}

/**
 * The reduced view for a template the person may run but not change (shared with run access, or an official locked one): what the
 * report covers, the filters its author left open, a file format, and Run. Nothing here is saved.
 */
export function RunWithOptions({ template, catalog }: Props) {
  const router = useRouter()
  const editable = template.userEditableFilters ?? []
  const [definition, setDefinition] = useState(template.definition)
  const [labels, setLabels] = useState<Record<string, string>>({})
  const allowed = catalog.settings.allowedFormats
  const [format, setFormat] = useState(allowed.includes(catalog.settings.defaultFormat) ? catalog.settings.defaultFormat : (allowed[0] ?? "csv"))
  const [tooLarge, setTooLarge] = useState(false)
  const start = useStartRun()

  const problems = validateDefinition(definition, catalog)
  const valid = Object.keys(problems).length === 0

  const run = () =>
    start.mutate(
      {
        templateId: template.id,
        format,
        filterOverrides: definition.filters.filter((f) => editable.includes(f.field)),
      },
      { onSuccess: () => { toast.success("Your report is being prepared."); router.push("/reports?tab=history") } }
    )

  return (
    <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_420px]">
      <div className="space-y-5">
        <div>
          <h1 className="mb-1.5 font-heading text-[26px] font-semibold tracking-[-0.01em]">{template.name}</h1>
          <p className="flex items-center gap-2 text-[13.5px] text-muted-foreground">
            <Lock aria-hidden className="size-4" />
            This report was set up by {template.ownerName ?? "someone else"}. You can run it
            {editable.length ? " and change the filters they left open." : "; its filters are fixed."}
          </p>
          {template.description && <p className="mt-2 text-[13.5px]">{template.description}</p>}
        </div>

        {(template.warnings ?? []).length > 0 && (
          <div role="alert" className="rounded-btn border border-amber-500/40 bg-amber-500/10 p-3 text-[13px]">
            <ul className="list-disc pl-5">
              {(template.warnings ?? []).map((w) => (
                <li key={w}>{w}</li>
              ))}
            </ul>
          </div>
        )}

        <section className="rounded-lg border border-border bg-card p-5">
          <h2 className="mb-3 text-[15px] font-semibold">Run with options</h2>
          <p className="mb-4 text-[13.5px] text-muted-foreground" data-testid="fixed-sentence">
            {summarise(template.definition, catalog)}
          </p>
          <FiltersStep definition={definition} catalog={catalog} problems={problems} editableFields={editable} onChange={setDefinition} onLabel={(k, l) => setLabels((x) => ({ ...x, [k]: l }))} />
          <div className="mt-5 flex flex-wrap items-center gap-3">
            <label className="text-[13.5px]" htmlFor="run-format">
              File format
            </label>
            <select id="run-format" className={SELECT_CLASS} value={format} onChange={(e) => setFormat(e.target.value)}>
              {allowed.map((f) => (
                <option key={f} value={f}>
                  {f.toUpperCase()}
                </option>
              ))}
            </select>
            <Button type="button" disabled={!valid || tooLarge || start.isPending || !catalog.capabilities.run} onClick={run}>
              <Play /> Run report
            </Button>
          </div>
        </section>
      </div>

      <div className="lg:sticky lg:top-4 lg:self-start">
        <PreviewPanel definition={definition} catalog={catalog} format={format} valid={valid} labels={labels} onTooLarge={setTooLarge} />
      </div>
    </div>
  )
}
