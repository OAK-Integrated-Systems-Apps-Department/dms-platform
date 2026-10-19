"use client"

import { useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { Loader2 } from "lucide-react"
import { toast } from "sonner"

import { Button } from "@/components/ui/button"
import { SearchableSelect } from "@/components/ui/searchable-select"
import { Switch } from "@/components/ui/switch"
import { ApiError } from "@/lib/api-error"
import {
  fetchWatermarkDefaults,
  updateWatermarkDefaults,
  type WatermarkDefaultsDto,
  type WatermarkTemplateDto,
} from "@/lib/watermarks/api"

interface WatermarkDownloadDefaultsCardProps {
  /** Active templates this tenant may choose from - system ones included, retired ones left out. */
  templates: WatermarkTemplateDto[]
  canEdit: boolean
}

/**
 * "Watermark every download by default" and "Watermark bulk exports by
 * default" - the tenant-wide policies for the plain Download action and for
 * a bulk/ZIP export's files, respectively (not the "Add watermark" copy
 * below, which a person asks for explicitly and which neither has any effect
 * on). Both share one default template.
 *
 * Sits above the templates table on the same screen rather than a separate
 * settings page: choosing the default template is meaningless without the
 * list of templates right there to pick from.
 *
 * Fetching lives in this outer component; the actual form
 * ({@link WatermarkDownloadDefaultsForm}) only mounts once the settings have
 * loaded, so its edit state can be seeded straight from props with
 * `useState`'s lazy initializer instead of an effect that copies server data
 * into state after the fact.
 */
export function WatermarkDownloadDefaultsCard({ templates, canEdit }: WatermarkDownloadDefaultsCardProps) {
  const { data, isPending, isError } = useQuery({
    queryKey: ["watermarks", "defaults"],
    queryFn: fetchWatermarkDefaults,
    retry: (failureCount, failure) =>
      !(failure instanceof ApiError && (failure.status === 404 || failure.status === 403)) && failureCount < 3,
  })

  if (isPending || isError || !data) {
    // A quiet skeleton rather than an error banner: the templates table
    // below carries the feature-off / network-error messaging already, and
    // duplicating it here would just be noise above it.
    return null
  }

  return <WatermarkDownloadDefaultsForm initial={data} templates={templates} canEdit={canEdit} />
}

interface WatermarkDownloadDefaultsFormProps {
  initial: WatermarkDefaultsDto
  templates: WatermarkTemplateDto[]
  canEdit: boolean
}

function WatermarkDownloadDefaultsForm({ initial, templates, canEdit }: WatermarkDownloadDefaultsFormProps) {
  const queryClient = useQueryClient()

  // Seeded once from the loaded settings. A save updates this same query's
  // cache (see onSuccess below) to exactly what was just saved, so the
  // values here never drift from what the server holds without another
  // edit happening first - no resync effect needed.
  const [enabled, setEnabled] = useState(initial.enabled)
  const [bulkExportEnabled, setBulkExportEnabled] = useState(initial.bulkExportEnabled)
  const [templateId, setTemplateId] = useState(initial.defaultTemplateId ?? "")

  const needsTemplate = enabled || bulkExportEnabled

  const save = useMutation({
    mutationFn: () =>
      updateWatermarkDefaults({
        enabled,
        bulkExportEnabled,
        templateId: needsTemplate ? templateId || null : null,
      }),
    onSuccess: (updated: WatermarkDefaultsDto) => {
      queryClient.setQueryData(["watermarks", "defaults"], updated)
      toast.success(
        updated.enabled || updated.bulkExportEnabled
          ? "Default watermarking settings saved."
          : "Default watermarking turned off."
      )
    },
    onError: (failure) =>
      toast.error(failure instanceof Error ? failure.message : "Could not save the download-watermarking settings."),
  })

  const changed =
    enabled !== initial.enabled ||
    bulkExportEnabled !== initial.bulkExportEnabled ||
    (needsTemplate && templateId !== (initial.defaultTemplateId ?? ""))
  const canSave = !needsTemplate || Boolean(templateId)

  return (
    <div className="mb-5 rounded-lg border border-border bg-card p-[22px] shadow-[var(--ledger-shadow)]">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="max-w-[560px]">
          <h2 className="mb-1 text-[14.5px] font-semibold">Watermark every download by default</h2>
          <p className="text-[12.5px] leading-[1.6] text-muted-foreground">
            When on, a plain Download of a supported file type is stamped with the chosen template
            automatically - no extra step, no separate copy to wait for. Unsupported file types and
            files with no template configured are downloaded unchanged.
          </p>
        </div>
        <label className="flex shrink-0 items-center gap-2.5 text-[13px] font-medium">
          <Switch checked={enabled} onCheckedChange={setEnabled} disabled={!canEdit || save.isPending} />
          {enabled ? "On" : "Off"}
        </label>
      </div>

      <div className="mt-4 flex flex-wrap items-start justify-between gap-4 border-t border-border pt-4">
        <div className="max-w-[560px]">
          <h2 className="mb-1 text-[14.5px] font-semibold">Watermark bulk exports by default</h2>
          <p className="text-[12.5px] leading-[1.6] text-muted-foreground">
            When on, every supported file in a bulk/ZIP export is stamped with the same default
            template as it&apos;s added to the archive. Off by default - exports are unchanged unless a
            tenant admin turns this on.
          </p>
        </div>
        <label className="flex shrink-0 items-center gap-2.5 text-[13px] font-medium">
          <Switch
            checked={bulkExportEnabled}
            onCheckedChange={setBulkExportEnabled}
            disabled={!canEdit || save.isPending}
          />
          {bulkExportEnabled ? "On" : "Off"}
        </label>
      </div>

      {needsTemplate && (
        <div className="mt-4 max-w-[360px]">
          <span className="mb-1.5 block text-[12.5px] font-medium">Default template</span>
          <SearchableSelect
            selected={templateId}
            onChange={setTemplateId}
            allLabel="Choose a template"
            options={templates.map((template) => ({
              value: template.templateId,
              label: template.systemTemplate ? `${template.label} (built-in)` : template.label,
            }))}
            className="w-full"
            searchable={templates.length > 6}
          />
        </div>
      )}

      {canEdit && (
        <div className="mt-4">
          <Button onClick={() => save.mutate()} disabled={!changed || !canSave || save.isPending}>
            {save.isPending && <Loader2 className="animate-spin" size={14} aria-hidden="true" />}
            Save
          </Button>
        </div>
      )}
    </div>
  )
}
