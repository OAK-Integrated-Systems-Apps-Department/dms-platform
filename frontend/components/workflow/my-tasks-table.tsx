"use client"

import Link from "next/link"
import { AlertTriangle, CheckCircle2, Forward, Hand, PauseCircle, TrendingUp, Undo2, XCircle } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { formatDateTime } from "@/lib/utils"

import type { MyTaskDto } from "@/lib/workflow/api"

import type { TaskDecisionKind } from "./task-action-dialog"

interface MyTasksTableProps {
  rows: MyTaskDto[]
  busy: boolean
  canApprove: boolean
  canReject: boolean
  onClaim: (task: MyTaskDto) => void
  onRelease: (task: MyTaskDto) => void
  onDecide: (task: MyTaskDto, decision: TaskDecisionKind) => void
  onDelegate: (task: MyTaskDto) => void
}

const TH = "px-3 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase"
const BTN = "flex items-center gap-1 border disabled:cursor-not-allowed disabled:opacity-50 h-[38px] rounded-btn px-4 text-[13px] font-semibold justify-center"

/** "Stage k of n: name" - the shared way a task's position in its workflow is described. */
export function stageLabel(task: Pick<MyTaskDto, "stageOrder" | "totalStages" | "stageName">) {
  return `Stage ${task.stageOrder} of ${task.totalStages}: ${task.stageName}`
}

/** A role/department pool task: one task for the whole pool, the first member to decide satisfies it. */
export function isPoolTask(task: MyTaskDto) {
  return task.assigneeType !== "user"
}

/** I hold it - addressed to me, claimed by me or delegated to me - so I may delegate it on (A4: not an unclaimed pool task). */
function iHold(task: MyTaskDto) {
  return task.route === "delegated" || task.claimedByMe || (task.route === "assigned" && task.assigneeType === "user")
}

/**
 * The My Tasks queue. Every row can be decided straight away - deciding an
 * unclaimed pool task claims it implicitly (the backend only lists pool
 * tasks that are unclaimed or claimed by me). Claim is optional, to signal
 * "I'm on it" and hide the task from the rest of the pool; Release gives it
 * back. Approve/Reject each need their own permission.
 */
export function MyTasksTable({ rows, busy, canApprove, canReject, onClaim, onRelease, onDecide, onDelegate }: MyTasksTableProps) {
  const canAct = canApprove || canReject
  return (
    <div className="overflow-hidden rounded-lg border border-border bg-card shadow-[var(--ledger-shadow)]">
      <div className="overflow-x-auto">
        <table className="w-full min-w-[1040px] border-collapse">
          <thead>
            <tr className="border-b border-border">
              <th className={TH}>Document</th>
              <th className={TH}>Workflow</th>
              <th className={TH}>Stage</th>
              <th className={TH}>Assigned Via</th>
              <th className={TH}>Due</th>
              <th className={TH}>Submitted By</th>
              <th className={`${TH} text-right`}>Actions</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && (
              <tr>
                <td colSpan={7} className="h-[max(160px,calc(100svh-430px))] px-3 py-10 text-center text-[13px] text-muted-foreground">
                  Nothing waiting for you here.
                </td>
              </tr>
            )}
            {rows.map((task) => {
              const pool = isPoolTask(task)
              const claimable = pool && task.status === "pending" && task.route === "pool"
              const waiting = task.status === "awaiting_info"
              return (
                <tr key={task.taskId} className="border-b border-border align-top last:border-b-0">
                  <td className="px-3 py-3">
                    <Link href={`/documents/${task.documentId}`} className="text-[13.5px] font-semibold text-primary hover:underline">
                      {task.documentTitle ?? "Untitled document"}
                    </Link>
                    <div className="mt-0.5 flex flex-wrap items-center gap-1.5 text-[12px] text-muted-foreground">
                      {task.documentTypeName ?? "—"}
                      {task.versionChangedFlag && (
                        <Badge variant="gold" title="A newer version was uploaded after submission">
                          <AlertTriangle /> Version changed
                        </Badge>
                      )}
                    </div>
                  </td>
                  <td className="px-3 py-3 text-[13px]">
                    <Link href={`/workflows/instances/${task.instanceId}`} className="text-foreground hover:text-primary hover:underline">
                      {task.templateName}
                    </Link>
                  </td>
                  <td className="px-3 py-3 text-[13px] text-foreground">{stageLabel(task)}</td>
                  <td className="px-3 py-3 text-[13px]">
                    {task.route === "delegated" ? (
                      <span className="flex flex-wrap items-center gap-1.5">
                        <Badge variant="gold"><Forward /> Delegated</Badge>
                        <span className="text-[12px] text-muted-foreground">from {task.delegatedFrom?.displayName ?? "an admin"}</span>
                      </span>
                    ) : task.route === "escalated" ? (
                      <Badge variant="rust"><TrendingUp /> Escalated to me</Badge>
                    ) : pool ? (
                      <>
                        <span className="text-foreground">{task.poolName ?? "Pool"}</span>
                        <span className="ml-1 text-[11.5px] text-muted-foreground">({task.assigneeType})</span>
                        {task.claimedByMe && (
                          <Badge variant="default" className="ml-1.5">Claimed by me</Badge>
                        )}
                      </>
                    ) : (
                      <span className="text-muted-foreground">Me</span>
                    )}
                  </td>
                  <td className="px-3 py-3">
                    {task.dueAt ? (
                      <div className="flex flex-col items-start gap-1">
                        <span className="font-mono text-[12.5px] text-muted-foreground">{formatDateTime(task.dueAt)}</span>
                        {task.overdue && <Badge variant="rust">Overdue</Badge>}
                        {waiting && (
                          <Badge variant="gold"><PauseCircle /> Waiting on info ({task.openInfoRequestCount})</Badge>
                        )}
                      </div>
                    ) : (
                      <span className="text-[13px] text-muted-foreground">—</span>
                    )}
                  </td>
                  <td className="px-3 py-3 text-[13px] text-foreground">{task.initiatedBy?.displayName ?? "—"}</td>
                  <td className="px-3 py-3">
                    <div className="flex flex-wrap justify-end gap-1.5">
                      {claimable && canAct && (
                        <button type="button" disabled={busy} onClick={() => onClaim(task)} className={`${BTN} border-primary bg-primary/10 text-primary`}>
                          <Hand size={13} /> Claim
                        </button>
                      )}
                      {iHold(task) && !waiting && canAct && (
                        <button type="button" disabled={busy} onClick={() => onDelegate(task)} className={`${BTN} border-[var(--border-strong)] bg-card text-foreground`}>
                          <Forward size={13} /> Delegate
                        </button>
                      )}
                      {pool && task.claimedByMe && !waiting && canAct && (
                        <button type="button" disabled={busy} onClick={() => onRelease(task)} className={`${BTN} border-[var(--border-strong)] bg-card text-muted-foreground`}>
                          <Undo2 size={13} /> Release
                        </button>
                      )}
                      {canApprove && !waiting && (
                        <button type="button" disabled={busy} onClick={() => onDecide(task, "approve")} className={`${BTN} border-primary bg-primary text-primary-foreground`}>
                          <CheckCircle2 size={13} /> Approve
                        </button>
                      )}
                      {canReject && !waiting && (
                        <button type="button" disabled={busy} onClick={() => onDecide(task, "reject")} className={`${BTN} border-[var(--rust)] bg-card text-[var(--rust)]`}>
                          <XCircle size={13} /> Reject
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </div>
  )
}
