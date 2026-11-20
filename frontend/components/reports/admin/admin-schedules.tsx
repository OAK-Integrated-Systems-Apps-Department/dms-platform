"use client"

import { useState } from "react"

import { Button } from "@/components/ui/button"
import { ConfirmActionDialog } from "@/components/ui/confirm-action-dialog"
import { LoadFailed } from "@/components/ui/load-failed"
import { LoadingState } from "@/components/ui/loading-state"
import { TablePagination } from "@/components/ui/table-pagination"
import type { AdminScheduleDto } from "@/lib/reports/api"
import { reportError } from "@/lib/reports/messages"
import { useAdminReassign, useAdminScheduleAction, useAdminSchedules } from "@/lib/reports/use-reports"

import { UserSelect } from "./principal-picker"

const STATUSES = [
  { value: "", label: "Any status" },
  { value: "active", label: "Active" },
  { value: "paused", label: "Paused" },
  { value: "cancelled", label: "Cancelled" },
  { value: "ended", label: "Ended" },
]

type Pending = { kind: "pause" | "resume" | "cancel"; schedule: AdminScheduleDto } | { kind: "reassign"; schedule: AdminScheduleDto }

const when = (iso: string | null, zone: string) =>
  iso ? new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short", timeZone: zone }).format(new Date(iso)) : "—"

/**
 * Every schedule in the organisation, metadata only (who owns it, when it runs, how it is going - never who receives it), with
 * pause, resume, cancel and hand-over. The server decides what is allowed and says why when it is not.
 */
export function AdminSchedules() {
  const [status, setStatus] = useState("")
  const [page, setPage] = useState(0)
  const list = useAdminSchedules({ status: status || undefined, page })
  const act = useAdminScheduleAction()
  const reassign = useAdminReassign()
  const [pending, setPending] = useState<Pending | null>(null)
  const [newOwner, setNewOwner] = useState("")

  async function confirm(): Promise<string | null> {
    if (!pending) return null
    try {
      if (pending.kind === "reassign") {
        await reassign.mutateAsync({ id: pending.schedule.id, newOwnerUserId: newOwner })
      } else {
        await act.mutateAsync({ id: pending.schedule.id, action: pending.kind })
      }
      setNewOwner("")
      return null
    } catch (error) {
      return reportError(error, "Could not change the schedule.")
    }
  }

  const verbs = { pause: "Pause", resume: "Resume", cancel: "Cancel" } as const

  return (
    <section className="rounded-lg border border-border bg-card" aria-label="All schedules">
      <header className="flex flex-wrap items-center justify-between gap-3 border-b border-border px-5 py-3.5">
        <div>
          <h2 className="text-[14.5px] font-semibold">All schedules</h2>
          <p className="mt-0.5 text-[12.5px] text-muted-foreground">Everyone&apos;s schedules. Recipients are not shown.</p>
        </div>
        <select aria-label="Status" className="h-9 rounded-md border border-input bg-transparent px-2.5 text-[13px]" value={status}
          onChange={(e) => { setStatus(e.target.value); setPage(0) }}>
          {STATUSES.map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}
        </select>
      </header>
      {list.isPending ? (
        <LoadingState message="Loading schedules…" />
      ) : list.isError || !list.data ? (
        <LoadFailed error={list.error} fallback="Could not load the schedules." onRetry={() => void list.refetch()} />
      ) : list.data.content.length === 0 ? (
        <p className="px-5 py-8 text-center text-[13px] text-muted-foreground">No schedules found.</p>
      ) : (
        <>
          <div className="overflow-x-auto">
            <table className="w-full text-left text-[13px]">
              <thead className="text-[12px] text-muted-foreground">
                <tr>
                  {["Schedule", "Owner", "Report", "Runs", "Next run", "Last result", "Recipients", ""].map((h) => (
                    <th key={h} className="px-4 py-2.5 font-medium">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {list.data.content.map((s) => (
                  <tr key={s.id}>
                    <td className="px-4 py-3 font-medium">{s.name}<div className="text-[12px] font-normal capitalize text-muted-foreground">{s.status}{s.pauseReason ? ` · ${s.pauseReason.toLowerCase().replace(/_/g, " ")}` : ""}</div></td>
                    <td className="px-4 py-3">{s.ownerName ?? "—"}</td>
                    <td className="px-4 py-3">{s.templateName ?? "—"}</td>
                    <td className="px-4 py-3 capitalize">{s.frequency}, {s.format.toUpperCase()}</td>
                    <td className="px-4 py-3">{when(s.nextRunAt, s.timeZone)}</td>
                    <td className="px-4 py-3">{s.lastFailureCode ? `Failed (${s.lastFailureCode})` : s.lastRunAt ? when(s.lastRunAt, s.timeZone) : "Not run yet"}</td>
                    <td className="px-4 py-3">{s.recipientCount}</td>
                    <td className="px-4 py-3">
                      <div className="flex flex-wrap justify-end gap-1.5">
                        {s.status === "active" && <Button size="xs" variant="outline" onClick={() => setPending({ kind: "pause", schedule: s })}>Pause</Button>}
                        {s.status === "paused" && <Button size="xs" variant="outline" onClick={() => setPending({ kind: "resume", schedule: s })}>Resume</Button>}
                        {(s.status === "active" || s.status === "paused") && (
                          <>
                            <Button size="xs" variant="outline" onClick={() => setPending({ kind: "reassign", schedule: s })}>Reassign</Button>
                            <Button size="xs" variant="outline" onClick={() => setPending({ kind: "cancel", schedule: s })}>Cancel</Button>
                          </>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {list.data.totalPages > 1 && (
            <TablePagination currentPage={page + 1} totalPages={list.data.totalPages} onPageChange={(p) => setPage(p - 1)} />
          )}
        </>
      )}
      {pending && (
        <ConfirmActionDialog
          open
          onOpenChange={(open) => { if (!open) { setPending(null); setNewOwner("") } }}
          title={pending.kind === "reassign" ? `Hand over “${pending.schedule.name}”` : `${verbs[pending.kind]} “${pending.schedule.name}”?`}
          description={
            pending.kind === "reassign"
              ? "The schedule runs with its owner's access, so the new owner must be allowed to run these reports and see the report."
              : pending.kind === "cancel"
                ? "It will stop for good. Its history is kept."
                : pending.kind === "pause"
                  ? "It will not run until it is resumed. Its history is kept."
                  : "It will run again at its next time."
          }
          confirmLabel={pending.kind === "reassign" ? "Hand over" : verbs[pending.kind]}
          pendingLabel="Working…"
          confirmDisabled={pending.kind === "reassign" && !newOwner}
          onConfirm={confirm}
        >
          {pending.kind === "reassign" && <UserSelect label="Choose the new owner" value={newOwner} onChange={setNewOwner} />}
        </ConfirmActionDialog>
      )}
    </section>
  )
}
