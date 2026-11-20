"use client"

import Link from "next/link"
import { useQueryClient } from "@tanstack/react-query"
import { useState } from "react"
import { toast } from "sonner"

import { RunDialog } from "@/components/reports/run-dialog"
import { ScheduleDialog } from "@/components/reports/schedule-dialog"
import { ShareDialog } from "@/components/reports/share-dialog"
import { Badge } from "@/components/ui/badge"
import { Button, buttonVariants } from "@/components/ui/button"
import { ConfirmActionDialog } from "@/components/ui/confirm-action-dialog"
import { LoadFailed } from "@/components/ui/load-failed"
import { LoadingState } from "@/components/ui/loading-state"
import type { TemplateDto } from "@/lib/reports/api"
import { reportError } from "@/lib/reports/messages"
import { deleteTemplate } from "@/lib/reports/api"
import { reportKeys, useDuplicateTemplate, useReportCatalog, useTemplates } from "@/lib/reports/use-reports"

const SCOPE_LABEL: Record<TemplateDto["scope"], string> = { private: "Private", shared: "Shared", official: "Official" }

type Dialog =
  | { kind: "run"; template: TemplateDto }
  | { kind: "schedule"; template: TemplateDto }
  | { kind: "share"; template: TemplateDto }
  | { kind: "delete"; template: TemplateDto }

/**
 * The reports the person owns or that were shared or published to them. What each row offers follows what they may do with it: run,
 * edit (owner or editor), duplicate, schedule, share and delete (owner). The server enforces all of it again.
 */
export function MyReportsTab({ onViewHistory, scheduleTemplateId }: { onViewHistory: () => void; scheduleTemplateId?: string | null }) {
  const templates = useTemplates()
  const catalog = useReportCatalog()
  const duplicate = useDuplicateTemplate()
  const queryClient = useQueryClient()
  const [chosen, setDialog] = useState<Dialog | null>(null)
  // "Save and schedule" in the builder arrives with the template to schedule: its schedule dialog opens once it is listed, until closed.
  const [handoverClosed, setHandoverClosed] = useState(false)
  const handoverTemplate = scheduleTemplateId ? templates.data?.find((t) => t.id === scheduleTemplateId) : undefined
  const dialog: Dialog | null =
    chosen ?? (handoverTemplate && !handoverClosed ? { kind: "schedule", template: handoverTemplate } : null)

  if (templates.isPending || catalog.isPending) return <LoadingState message="Loading your reports…" />
  if (templates.isError || catalog.isError || !catalog.data) {
    return (
      <LoadFailed
        error={templates.error ?? catalog.error}
        fallback="Could not load your reports."
        onRetry={() => {
          void templates.refetch()
          void catalog.refetch()
        }}
      />
    )
  }

  const caps = catalog.data.capabilities
  const canSchedule = caps.schedule && catalog.data.settings.schedulingEnabled
  const rows = templates.data ?? []

  return (
    <div className="flex flex-col gap-3">
      {caps.build && (
        <div className="flex justify-end">
          <Link href="/reports/builder" className={buttonVariants()}>New report</Link>
        </div>
      )}

      {rows.length === 0 ? (
        <div className="rounded-lg border border-dashed border-[var(--border-strong)] bg-card px-6 py-12 text-center text-[13px] text-muted-foreground">
          <p>You have no saved reports yet.</p>
          {caps.build && <p className="mt-1">Choose “New report” to build one.</p>}
        </div>
      ) : (
        <div className="overflow-x-auto rounded-lg border border-border bg-card">
          <table className="w-full text-left text-[13px]">
            <thead className="border-b border-border text-[12px] text-muted-foreground">
              <tr>
                <th className="px-4 py-2.5 font-medium">Report</th>
                <th className="px-4 py-2.5 font-medium">Kind</th>
                <th className="px-4 py-2.5 font-medium">Owner</th>
                <th className="px-4 py-2.5 font-medium">Schedules</th>
                <th className="px-4 py-2.5 text-right font-medium">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {rows.map((t) => {
                const mayEdit = t.access === "owner" || t.access === "edit"
                return (
                  <tr key={t.id}>
                    <td className="px-4 py-3">
                      <div className="font-medium">{t.name}</div>
                      {t.description && <div className="text-[12.5px] text-muted-foreground">{t.description}</div>}
                      {(t.warnings ?? []).map((w) => (
                        <div key={w} className="mt-1 text-[12px] text-[var(--rust)]">
                          {w}
                        </div>
                      ))}
                    </td>
                    <td className="px-4 py-3">
                      <Badge variant={t.scope === "official" ? "gold" : t.scope === "shared" ? "default" : "muted"}>{SCOPE_LABEL[t.scope]}</Badge>
                      {t.locked && <span className="ml-1.5 text-[12px] text-muted-foreground">locked</span>}
                    </td>
                    <td className="px-4 py-3">{t.ownerName ?? "—"}</td>
                    <td className="px-4 py-3">{t.activeSchedules}</td>
                    <td className="px-4 py-3">
                      <div className="flex flex-wrap justify-end gap-1.5">
                        <Button size="xs" onClick={() => setDialog({ kind: "run", template: t })}>
                          Run
                        </Button>
                        {mayEdit && caps.build && !t.locked && (
                          <Link href={`/reports/builder/${t.id}`} className={buttonVariants({ size: "xs", variant: "outline" })}>
                            Edit
                          </Link>
                        )}
                        {caps.build && (
                          <Button size="xs" variant="outline" disabled={duplicate.isPending} onClick={() => duplicate.mutate(t.id)}>
                            Duplicate
                          </Button>
                        )}
                        {canSchedule && (
                          <Button size="xs" variant="outline" onClick={() => setDialog({ kind: "schedule", template: t })}>
                            Schedule
                          </Button>
                        )}
                        {t.access === "owner" && t.scope !== "official" && (
                          <Button size="xs" variant="outline" onClick={() => setDialog({ kind: "share", template: t })}>
                            Share
                          </Button>
                        )}
                        {t.access === "owner" && (
                          <Button size="xs" variant="destructive" onClick={() => setDialog({ kind: "delete", template: t })}>
                            Delete
                          </Button>
                        )}
                      </div>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}

      {dialog?.kind === "run" && (
        <RunDialog
          template={dialog.template}
          catalog={catalog.data}
          onClose={() => {
          setDialog(null)
          setHandoverClosed(true)
        }}
          onStarted={() =>
            toast.success("Your report is being prepared.", { action: { label: "View run history", onClick: onViewHistory } })
          }
        />
      )}
      {dialog?.kind === "schedule" && (
        <ScheduleDialog catalog={catalog.data} template={dialog.template} onClose={() => {
          setDialog(null)
          setHandoverClosed(true)
        }} />
      )}
      {dialog?.kind === "share" && <ShareDialog template={dialog.template} onClose={() => {
          setDialog(null)
          setHandoverClosed(true)
        }} />}
      {dialog?.kind === "delete" && (
        <ConfirmActionDialog
          open
          onOpenChange={(open) => !open && setDialog(null)}
          title="Delete this report?"
          description={`“${dialog.template.name}” will be removed from your reports. Files already made from it are kept until they expire.`}
          confirmLabel="Delete report"
          pendingLabel="Deleting…"
          onConfirm={async () => {
            try {
              await deleteTemplate(dialog.template.id)
              toast.success("Report deleted.")
              void queryClient.invalidateQueries({ queryKey: reportKeys.templates })
              return null
            } catch (error) {
              return reportError(error, "Could not delete the report.")
            }
          }}
        />
      )}
    </div>
  )
}
