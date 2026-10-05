"use client"

import {
  AlertTriangle,
  Ban,
  CheckCircle2,
  CircleDot,
  FileUp,
  Flag,
  Hand,
  RotateCcw,
  Send,
  Undo2,
  XCircle,
  type LucideIcon,
} from "lucide-react"

import { useWorkflowTimeline } from "@/lib/workflow/use-workflow-instance"
import { ACTION_TYPE_LABELS } from "@/lib/workflow/workflow-badges"
import { cn, formatDateTime } from "@/lib/utils"

import type { WorkflowActionDto, WorkflowActionType } from "@/lib/workflow/api"

const ACTION_ICON: Record<WorkflowActionType, { icon: LucideIcon; className: string }> = {
  submitted: { icon: Send, className: "text-primary" },
  claimed: { icon: Hand, className: "text-muted-foreground" },
  released: { icon: Undo2, className: "text-muted-foreground" },
  approved: { icon: CheckCircle2, className: "text-primary" },
  rejected: { icon: XCircle, className: "text-[var(--rust)]" },
  stage_activated: { icon: CircleDot, className: "text-[var(--gold-foreground)]" },
  stage_approved: { icon: CheckCircle2, className: "text-primary" },
  stage_rejected: { icon: XCircle, className: "text-[var(--rust)]" },
  stage_unassignable: { icon: AlertTriangle, className: "text-[var(--rust)]" },
  workflow_approved: { icon: Flag, className: "text-primary" },
  workflow_rejected: { icon: Flag, className: "text-[var(--rust)]" },
  cancelled: { icon: Ban, className: "text-muted-foreground" },
  version_changed: { icon: FileUp, className: "text-[var(--gold-foreground)]" },
  restarted: { icon: RotateCcw, className: "text-[var(--gold-foreground)]" },
}

/** Person-performed actions read "{actor} {label}"; system events (null actor) read as the label on its own. */
function describe(action: WorkflowActionDto) {
  const label = ACTION_TYPE_LABELS[action.actionType] ?? action.actionType
  if (!action.actor) return label
  return `${action.actor.displayName ?? "Someone"} ${label}`
}

/** The run's full history (`GET /workflow/instances/{id}/actions`), oldest first, with each decision's reason/comment. */
export function WorkflowTimeline({ instanceId }: { instanceId: string }) {
  const { data, isPending, isError, error } = useWorkflowTimeline(instanceId)

  if (isPending) return <p className="pt-3 text-[13px] text-muted-foreground">Loading history…</p>
  if (isError) {
    return (
      <p className="pt-3 text-[13px] text-[var(--rust)]">
        {error instanceof Error ? error.message : "Could not load this workflow's history."}
      </p>
    )
  }
  if (data.content.length === 0) return <p className="pt-3 text-[13px] text-muted-foreground">No activity yet.</p>

  return (
    <ol className="relative pt-3">
      {data.content.map((action, index) => {
        const { icon: Icon, className } = ACTION_ICON[action.actionType] ?? { icon: CircleDot, className: "text-muted-foreground" }
        const last = index === data.content.length - 1
        return (
          <li key={action.actionId} className="relative flex gap-3 pb-4">
            {!last && <span className="absolute top-6 bottom-0 left-[11px] w-px bg-border" aria-hidden="true" />}
            <span className="relative z-[1] flex size-6 shrink-0 items-center justify-center rounded-full border border-border bg-card">
              <Icon size={13} className={className} />
            </span>
            <div className="min-w-0 flex-1">
              <p className="text-[13px] text-foreground">
                <span className={cn(!action.actor && "font-medium")}>{describe(action)}</span>
                {action.stageOrder != null && (
                  <span className="ml-1.5 text-[12px] text-muted-foreground">· Stage {action.stageOrder}</span>
                )}
              </p>
              {action.reason && (
                <p className="mt-1 rounded-sm border-l-2 border-[var(--border-strong)] bg-muted px-2.5 py-1.5 text-[12.5px] whitespace-pre-wrap text-foreground">
                  {action.reason}
                </p>
              )}
              <p className="mt-0.5 font-mono text-[11.5px] text-muted-foreground">{formatDateTime(action.occurredAt)}</p>
            </div>
          </li>
        )
      })}
    </ol>
  )
}
