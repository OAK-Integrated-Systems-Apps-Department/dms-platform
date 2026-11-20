"use client"

import { useState } from "react"
import Link from "next/link"
import { Lock } from "lucide-react"

import { Button, buttonVariants } from "@/components/ui/button"
import { ConfirmActionDialog } from "@/components/ui/confirm-action-dialog"
import { LoadFailed } from "@/components/ui/load-failed"
import { LoadingState } from "@/components/ui/loading-state"
import { Switch } from "@/components/ui/switch"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import type { Grant, TemplateDto } from "@/lib/reports/api"
import { reportError } from "@/lib/reports/messages"
import { usePublishTemplate, useReportCatalog, useTemplate, useTemplates, useUnpublishTemplate } from "@/lib/reports/use-reports"

import { GrantPicker } from "./principal-picker"

/**
 * Official templates: the organisation's own reports that everyone it is published to can run. A locked one cannot be changed by
 * the people running it, except for the filters chosen here. Anyone who may publish can also publish one of their own reports.
 */
export function OfficialTemplatesTab() {
  const { hasPermission } = usePermissions()
  const canPublish = hasPermission(PermissionCodes.CRG_TEMPLATE_PUBLISH)
  const canBuild = hasPermission(PermissionCodes.CRG_BUILD)
  const templates = useTemplates()
  const unpublish = useUnpublishTemplate()
  const [publishing, setPublishing] = useState<TemplateDto | null>(null)
  const [unpublishing, setUnpublishing] = useState<TemplateDto | null>(null)

  if (templates.isPending) return <LoadingState message="Loading reports…" />
  if (templates.isError || !templates.data) {
    return <LoadFailed error={templates.error} fallback="Could not load the reports." onRetry={() => void templates.refetch()} />
  }
  const official = templates.data.filter((t) => t.scope === "official")
  const mine = templates.data.filter((t) => t.scope !== "official" && t.access === "owner")

  const table = (rows: TemplateDto[], empty: string) =>
    rows.length === 0 ? (
      <p className="px-5 py-6 text-center text-[13px] text-muted-foreground">{empty}</p>
    ) : (
      <ul className="divide-y divide-border">
        {rows.map((t) => (
          <li key={t.id} className="flex flex-wrap items-center justify-between gap-3 px-5 py-3.5">
            <div className="min-w-[220px] flex-1">
              <p className="flex items-center gap-1.5 text-[13.5px] font-medium">
                {t.name}
                {t.locked && <span className="inline-flex items-center gap-1 rounded-full bg-muted px-2 py-0.5 text-[11.5px] font-normal"><Lock className="size-3" />Locked</span>}
              </p>
              <p className="text-[12.5px] text-muted-foreground">
                {t.description || "No description."}
                {t.scope === "official" && t.userEditableFilters && t.userEditableFilters.length > 0 && ` People may change ${t.userEditableFilters.length} filter(s).`}
              </p>
            </div>
            <div className="flex flex-wrap gap-1.5">
              {canBuild && <Link href={`/reports/builder/${t.id}`} className={buttonVariants({ size: "xs", variant: "outline" })}>Edit in builder</Link>}
              {canPublish && <Button size="xs" variant="outline" onClick={() => setPublishing(t)}>{t.scope === "official" ? "Change publishing" : "Publish"}</Button>}
              {canPublish && t.scope === "official" && <Button size="xs" variant="outline" onClick={() => setUnpublishing(t)}>Unpublish</Button>}
            </div>
          </li>
        ))}
      </ul>
    )

  return (
    <div className="flex w-full flex-col gap-4">
      <section className="rounded-lg border border-border bg-card" aria-label="Official templates">
        <header className="border-b border-border px-5 py-3.5">
          <h2 className="text-[14.5px] font-semibold">Official templates</h2>
          <p className="mt-0.5 text-[12.5px] text-muted-foreground">Published to everyone, or to the audience you choose.</p>
          {canBuild && <Link href="/reports/builder" className={buttonVariants({ size: "xs" }) + " mt-2"}>New report</Link>}
        </header>
        {table(official, "No official templates yet.")}
      </section>
      {canPublish && (
        <section className="rounded-lg border border-border bg-card" aria-label="Your reports">
          <header className="border-b border-border px-5 py-3.5">
            <h2 className="text-[14.5px] font-semibold">Your reports</h2>
            <p className="mt-0.5 text-[12.5px] text-muted-foreground">Publish one of your own reports to make it official.</p>
          </header>
          {table(mine, "You have no reports of your own to publish.")}
        </section>
      )}
      {publishing && <PublishDialog template={publishing} onClose={() => setPublishing(null)} />}
      {unpublishing && (
        <ConfirmActionDialog
          open
          onOpenChange={(open) => { if (!open) setUnpublishing(null) }}
          title={`Unpublish “${unpublishing.name}”?`}
          description="It goes back to being private to its owner. Schedules that use it keep working for the people who can still see it."
          confirmLabel="Unpublish"
          pendingLabel="Unpublishing…"
          onConfirm={async () => {
            try {
              await unpublish.mutateAsync(unpublishing.id)
              return null
            } catch (error) {
              return reportError(error, "Could not unpublish the report.")
            }
          }}
        />
      )}
    </div>
  )
}

/**
 * The list leaves out who an official template is published to, so changing one first loads the full template: the form then opens
 * on its saved lock and audience instead of the defaults (which would silently widen it to everyone).
 */
function PublishDialog({ template, onClose }: { template: TemplateDto; onClose: () => void }) {
  const official = template.scope === "official"
  const detail = useTemplate(official ? template.id : null)
  if (official && detail.isPending) return null
  return <PublishForm template={official && detail.data ? detail.data : template} onClose={onClose} />
}

function PublishForm({ template, onClose }: { template: TemplateDto; onClose: () => void }) {
  const publish = usePublishTemplate()
  const catalog = useReportCatalog()
  const [locked, setLocked] = useState(template.locked)
  const [editable, setEditable] = useState<string[]>(template.userEditableFilters ?? [])
  const [audience, setAudience] = useState<Grant[]>((template.grants ?? []).filter((g) => g.accessLevel === "run"))
  const labels = new Map((catalog.data?.fields ?? []).map((f) => [f.key, f.label]))
  const filterFields = [...new Set(template.definition.filters.map((f) => f.field))]

  return (
    <ConfirmActionDialog
      open
      onOpenChange={(open) => { if (!open) onClose() }}
      title={`Publish “${template.name}”`}
      description="Everyone in the audience can run it. They see only the documents they may see themselves."
      confirmLabel="Publish"
      pendingLabel="Publishing…"
      onConfirm={async () => {
        try {
          await publish.mutateAsync({ id: template.id, request: { locked, userEditableFilters: locked ? editable : filterFields, audience } })
          return null
        } catch (error) {
          return reportError(error, "Could not publish the report.")
        }
      }}
    >
      <div className="mt-3 flex flex-col gap-4 text-left">
        <label className="flex items-center gap-2.5 text-[13px] font-medium">
          <Switch checked={locked} onCheckedChange={setLocked} aria-label="Lock the report" />
          Lock the report (people can run it but not change what it shows)
        </label>
        {locked && (
          <fieldset className="flex flex-col gap-1.5">
            <legend className="mb-1 text-[13px] font-medium">Filters people may still change</legend>
            {filterFields.length === 0 && <p className="text-[12.5px] text-muted-foreground">This report has no filters.</p>}
            {filterFields.map((key) => (
              <label key={key} className="flex items-center gap-2 text-[13px]">
                <input type="checkbox" checked={editable.includes(key)}
                  onChange={(e) => setEditable(e.target.checked ? [...editable, key] : editable.filter((k) => k !== key))} />
                {labels.get(key) ?? key}
              </label>
            ))}
          </fieldset>
        )}
        <div>
          <p className="mb-1.5 text-[13px] font-medium">Audience</p>
          <GrantPicker value={audience} onChange={setAudience} />
        </div>
      </div>
    </ConfirmActionDialog>
  )
}
