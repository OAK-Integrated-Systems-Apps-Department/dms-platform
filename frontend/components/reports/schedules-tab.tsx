"use client"

import { useState } from "react"

import { ScheduleDialog } from "@/components/reports/schedule-dialog"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { ConfirmActionDialog } from "@/components/ui/confirm-action-dialog"
import { LoadFailed } from "@/components/ui/load-failed"
import { LoadingState } from "@/components/ui/loading-state"
import { cancelSchedule, type ScheduleDto } from "@/lib/reports/api"
import { describeFrequency, formatInZone } from "@/lib/reports/format"
import { reportError } from "@/lib/reports/messages"
import {
  reportKeys,
  usePauseSchedule,
  useReportCatalog,
  useResumeSchedule,
  useRunScheduleNow,
  useSchedules,
} from "@/lib/reports/use-reports"
import { useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

const PAUSE_REASONS: Record<string, string> = {
  ADMIN: "Paused by an administrator",
  OWNER_INACTIVE: "Its owner is no longer an active user",
  paused_failed: "Paused after repeated failures",
  FAILURES: "Paused after repeated failures",
  OWNER_PERMISSION_LOST: "Its owner can no longer run reports",
  TEMPLATE_UNAVAILABLE: "Its report is no longer available",
}

const STATUS_LABEL: Record<string, string> = { active: "Active", paused: "Paused", cancelled: "Cancelled", ended: "Ended" }

/** The person's schedules: when each runs next (in the schedule's own time zone), how the last run went, and what can be done. */
export function SchedulesTab() {
  const schedules = useSchedules()
  const catalog = useReportCatalog()
  const pause = usePauseSchedule()
  const resume = useResumeSchedule()
  const runNow = useRunScheduleNow()
  const queryClient = useQueryClient()
  const [editing, setEditing] = useState<ScheduleDto | null>(null)
  const [cancelling, setCancelling] = useState<ScheduleDto | null>(null)

  if (schedules.isPending || catalog.isPending) return <LoadingState message="Loading your schedules…" />
  if (schedules.isError || catalog.isError || !catalog.data) {
    return (
      <LoadFailed
        error={schedules.error ?? catalog.error}
        fallback="Could not load your schedules."
        onRetry={() => {
          void schedules.refetch()
          void catalog.refetch()
        }}
      />
    )
  }

  const rows = (schedules.data ?? []).filter((s) => s.status !== "cancelled")

  return (
    <div className="flex flex-col gap-3">
      {rows.length === 0 ? (
        <div className="rounded-lg border border-dashed border-[var(--border-strong)] bg-card px-6 py-12 text-center text-[13px] text-muted-foreground">
          <p>You have no schedules.</p>
          <p className="mt-1">Open “My reports” and choose Schedule on a report to send it automatically.</p>
        </div>
      ) : (
        <div className="overflow-x-auto rounded-lg border border-border bg-card">
          <table className="w-full text-left text-[13px]">
            <thead className="border-b border-border text-[12px] text-muted-foreground">
              <tr>
                <th className="px-4 py-2.5 font-medium">Schedule</th>
                <th className="px-4 py-2.5 font-medium">When</th>
                <th className="px-4 py-2.5 font-medium">Next run</th>
                <th className="px-4 py-2.5 font-medium">Last result</th>
                <th className="px-4 py-2.5 font-medium">Status</th>
                <th className="px-4 py-2.5 text-right font-medium">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {rows.map((s) => (
                <tr key={s.id}>
                  <td className="px-4 py-3">
                    <div className="font-medium">{s.name}</div>
                    <div className="text-[12.5px] text-muted-foreground">{s.templateName ?? "—"}</div>
                  </td>
                  <td className="px-4 py-3">
                    {describeFrequency(s)}
                    <div className="text-[12px] text-muted-foreground">{s.timeZone}</div>
                  </td>
                  <td className="px-4 py-3">{s.status === "active" ? formatInZone(s.nextRunAt, s.timeZone) : "—"}</td>
                  <td className="px-4 py-3">
                    {s.lastFailureCode ? (
                      <span className="text-destructive">
                        Failed ({s.lastFailureCode}){s.consecutiveFailures > 1 ? `, ${s.consecutiveFailures} in a row` : ""}
                      </span>
                    ) : s.lastRunAt ? (
                      <>Sent {formatInZone(s.lastRunAt, s.timeZone)}</>
                    ) : (
                      <span className="text-muted-foreground">Not run yet</span>
                    )}
                  </td>
                  <td className="px-4 py-3">
                    <Badge variant={s.status === "active" ? "default" : "muted"}>{STATUS_LABEL[s.status] ?? s.status}</Badge>
                    {s.status === "paused" && s.pauseReason && (
                      <div className="mt-1 text-[12px] text-muted-foreground">{PAUSE_REASONS[s.pauseReason] ?? s.pauseReason}</div>
                    )}
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex flex-wrap justify-end gap-1.5">
                      {s.status === "active" && (
                        <Button size="xs" variant="outline" disabled={pause.isPending} onClick={() => pause.mutate(s.id)}>
                          Pause
                        </Button>
                      )}
                      {s.status === "paused" && (
                        <Button size="xs" variant="outline" disabled={resume.isPending} onClick={() => resume.mutate(s.id)}>
                          Resume
                        </Button>
                      )}
                      {(s.status === "active" || s.status === "paused") && (
                        <>
                          <Button size="xs" variant="outline" disabled={runNow.isPending} onClick={() => runNow.mutate(s.id)}>
                            Run now
                          </Button>
                          <Button size="xs" variant="outline" onClick={() => setEditing(s)}>
                            Edit
                          </Button>
                          <Button size="xs" variant="destructive" onClick={() => setCancelling(s)}>
                            Cancel
                          </Button>
                        </>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {editing && (
        <ScheduleDialog
          catalog={catalog.data}
          template={{ id: editing.templateId, name: editing.templateName ?? "Report" }}
          schedule={editing}
          onClose={() => setEditing(null)}
        />
      )}
      {cancelling && (
        <ConfirmActionDialog
          open
          onOpenChange={(open) => !open && setCancelling(null)}
          title="Cancel this schedule?"
          description={`“${cancelling.name}” will stop running. Its history is kept.`}
          confirmLabel="Cancel schedule"
          pendingLabel="Cancelling…"
          cancelLabel="Keep it"
          onConfirm={async () => {
            try {
              await cancelSchedule(cancelling.id)
              toast.success("Schedule cancelled.")
              void queryClient.invalidateQueries({ queryKey: reportKeys.schedules })
              void queryClient.invalidateQueries({ queryKey: reportKeys.templates })
              return null
            } catch (error) {
              return reportError(error, "Could not cancel the schedule.")
            }
          }}
        />
      )}
    </div>
  )
}
