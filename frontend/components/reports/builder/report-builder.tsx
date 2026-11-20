"use client"

import { useEffect, useState } from "react"
import { useRouter } from "next/navigation"
import { CalendarClock, Copy, Play, Save } from "lucide-react"
import { toast } from "sonner"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { LoadFailed } from "@/components/ui/load-failed"
import { LoadingState } from "@/components/ui/loading-state"
import { SegmentedControl } from "@/components/ui/segmented-control"
import { StepCard } from "@/components/ui/step-card"
import { Textarea } from "@/components/ui/textarea"
import type { CatalogDto, TemplateDto } from "@/lib/reports/api"
import { emptyDefinition, validateDefinition, type ReportDefinition } from "@/lib/reports/definition"
import { reportError } from "@/lib/reports/messages"
import {
  useCreateTemplate,
  useReportCatalog,
  useStartRun,
  useTemplate,
  useUpdateTemplate,
} from "@/lib/reports/use-reports"

import { ColumnsStep } from "./columns-step"
import { FiltersStep } from "./filters-step"
import { PreviewPanel } from "./preview-panel"
import { RunWithOptions } from "./run-with-options"

interface Props {
  /** Present when editing a saved report. */
  templateId?: string
}

/** Loads what the builder needs, then hands over to the form (or to the reduced view for a report that is not theirs to change). */
export function ReportBuilder({ templateId }: Props) {
  const catalog = useReportCatalog()
  const template = useTemplate(templateId ?? null)

  if (catalog.isLoading || (templateId && template.isLoading)) return <LoadingState />
  if (catalog.isError || !catalog.data) return <LoadFailed error={catalog.error} fallback="Could not load the report builder." onRetry={() => void catalog.refetch()} />
  if (templateId && (template.isError || !template.data)) {
    return <LoadFailed error={template.error} fallback="Could not load this report." onRetry={() => void template.refetch()} />
  }

  const loaded = templateId ? template.data : undefined
  if (loaded && isReduced(loaded, catalog.data)) return <RunWithOptions template={loaded} catalog={catalog.data} />
  return <BuilderForm key={loaded?.id ?? "new"} catalog={catalog.data} template={loaded} />
}

/** The person may run this report but not change it: shared with run access only, or locked by its publisher. */
export function isReduced(template: TemplateDto, catalog: CatalogDto): boolean {
  return template.access === "run" || (template.locked && template.access !== "owner") || !catalog.capabilities.build
}

function BuilderForm({ catalog, template }: { catalog: CatalogDto; template?: TemplateDto }) {
  const router = useRouter()
  // What is "saved" - the page warns about leaving only when something differs from it.
  const [baseline, setBaseline] = useState(() => ({
    definition: template?.definition ?? emptyDefinition(catalog),
    name: template?.name ?? "",
    description: template?.description ?? "",
  }))
  const allowed = catalog.settings.allowedFormats
  const [definition, setDefinition] = useState<ReportDefinition>(baseline.definition)
  const [name, setName] = useState(baseline.name)
  const [description, setDescription] = useState(baseline.description)
  const [format, setFormat] = useState(allowed.includes(catalog.settings.defaultFormat) ? catalog.settings.defaultFormat : (allowed[0] ?? "csv"))
  const [labels, setLabels] = useState<Record<string, string>>({})
  const [tooLarge, setTooLarge] = useState(false)
  const [attempted, setAttempted] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)

  const create = useCreateTemplate()
  const update = useUpdateTemplate()
  const start = useStartRun()

  const problems = validateDefinition(definition, catalog)
  const valid = Object.keys(problems).length === 0
  const nameProblem = name.trim() === "" ? "Give the report a name." : name.length > 150 ? "Use at most 150 characters." : null
  const shown = attempted ? problems : {}

  const dirty =
    JSON.stringify(definition) !== JSON.stringify(baseline.definition) || name !== baseline.name || description !== baseline.description

  useEffect(() => {
    if (!dirty) return
    const guard = (event: BeforeUnloadEvent) => {
      event.preventDefault()
    }
    window.addEventListener("beforeunload", guard)
    return () => window.removeEventListener("beforeunload", guard)
  }, [dirty])

  const canRun = catalog.capabilities.build && catalog.capabilities.run
  const busy = create.isPending || update.isPending

  /** Saves (creating or updating) and returns the report, or null when something stopped it. */
  async function save(asCopy = false): Promise<TemplateDto | null> {
    setAttempted(true)
    setSaveError(null)
    if (!valid || nameProblem) return null
    const request = { name: asCopy ? `${name.trim()} (copy)` : name.trim(), description: description.trim() || null, definition }
    try {
      return template && !asCopy ? await update.mutateAsync({ id: template.id, request }) : await create.mutateAsync(request)
    } catch (error) {
      setSaveError(reportError(error, "Could not save the report."))
      return null
    }
  }

  async function saveAndGo(to: (saved: TemplateDto) => string, asCopy = false) {
    const saved = await save(asCopy)
    if (saved) {
      // What was just saved is no longer "unsaved": let the page leave without the browser's warning.
      setBaseline({ definition, name, description })
      router.push(to(saved))
    }
  }

  function runNow() {
    setAttempted(true)
    if (!valid) return
    start.mutate({ definition, format }, { onSuccess: () => { toast.success("Your report is being prepared."); router.push("/reports?tab=history") } })
  }

  const warnings = template?.warnings ?? []

  return (
    <>
      <AdminBreadcrumb className="mb-3" segments={[{ label: "Reports", href: "/reports" }, { label: template ? template.name : "New report" }]} />
      <div className="mb-5">
        <h1 className="mb-1.5 font-heading text-[26px] font-semibold tracking-[-0.01em]">{template ? "Edit report" : "Build a report"}</h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Choose which documents to include, what to show about them, and how to receive the result. The panel on the right shows what you will get.
        </p>
      </div>

      {warnings.length > 0 && (
        <div role="alert" className="mb-5 rounded-btn border border-amber-500/40 bg-amber-500/10 p-3 text-[13px]">
          <p className="mb-1 font-medium">Some parts of this report are no longer available and were left out:</p>
          <ul className="list-disc pl-5">
            {warnings.map((w) => (
              <li key={w}>{w}</li>
            ))}
          </ul>
        </div>
      )}

      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_420px]">
        <div className="space-y-5">
          <StepCard step={1} title="What to include" description="Narrow the report to the documents you care about.">
            <FiltersStep definition={definition} catalog={catalog} problems={shown} onChange={setDefinition} onLabel={(k, l) => setLabels((x) => ({ ...x, [k]: l }))} />
          </StepCard>

          <StepCard step={2} title="What to show" description="Choose the columns, their order, and how rows are sorted.">
            <ColumnsStep definition={definition} catalog={catalog} problems={problems} onChange={setDefinition} />
          </StepCard>

          <StepCard step={3} title="Format" description="The file you get when you run the report.">
            <SegmentedControl
              aria-label="File format"
              value={format}
              onChange={setFormat}
              options={allowed.map((f) => ({ value: f, label: f.toUpperCase() }))}
            />
            {format === "pdf" && (
              <p className="mt-3 text-[12.5px] text-muted-foreground">PDF files use the page size, orientation, header and footer your administrator has set.</p>
            )}
          </StepCard>

          <StepCard step={4} title="Save and schedule" description="Keep this report to run again, or have it sent on a schedule.">
            <div className="space-y-4">
              <div>
                <Label htmlFor="report-name">Name</Label>
                <Input id="report-name" value={name} maxLength={150} aria-invalid={attempted && Boolean(nameProblem)} onChange={(e) => setName(e.target.value)} />
                {attempted && nameProblem && (
                  <p role="alert" className="mt-1 text-[12.5px] text-destructive">
                    {nameProblem}
                  </p>
                )}
              </div>
              <div>
                <Label htmlFor="report-description">Description (optional)</Label>
                <Textarea id="report-description" rows={2} value={description} onChange={(e) => setDescription(e.target.value)} />
              </div>
              {saveError && (
                <p role="alert" className="text-[13px] text-destructive">
                  {saveError}
                </p>
              )}
              {attempted && !valid && (
                <p role="alert" className="text-[13px] text-destructive">
                  Fix the highlighted parts above first.
                </p>
              )}
              <div className="flex flex-wrap gap-2">
                <Button type="button" disabled={busy} onClick={() => void saveAndGo((s) => `/reports/builder/${s.id}`)}>
                  <Save /> Save
                </Button>
                {template && (
                  <Button type="button" variant="outline" disabled={busy} onClick={() => void saveAndGo((s) => `/reports/builder/${s.id}`, true)}>
                    <Copy /> Save as copy
                  </Button>
                )}
                {canRun && (
                  <Button type="button" variant="outline" disabled={start.isPending || tooLarge} onClick={runNow}>
                    <Play /> Run now
                  </Button>
                )}
                {catalog.capabilities.schedule && catalog.settings.schedulingEnabled && (
                  <Button type="button" variant="outline" disabled={busy} onClick={() => void saveAndGo((s) => `/reports?tab=schedules&schedule=${s.id}`)}>
                    <CalendarClock /> Save and schedule
                  </Button>
                )}
              </div>
            </div>
          </StepCard>
        </div>

        <div className="lg:sticky lg:top-4 lg:self-start">
          <PreviewPanel definition={definition} catalog={catalog} format={format} valid={valid} labels={labels} onTooLarge={setTooLarge} />
        </div>
      </div>
    </>
  )
}
