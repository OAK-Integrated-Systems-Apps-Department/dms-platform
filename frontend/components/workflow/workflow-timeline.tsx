"use client"

import {
  AlertTriangle,
  Ban,
  BellRing,
  CheckCircle2,
  CircleDot,
  FileMinus,
  FilePlus,
  FileUp,
  Flag,
  Forward,
  Hand,
  MessageCircleQuestion,
  MessageSquare,
  MessageSquareReply,
  MessageSquareX,
  RotateCcw,
  Send,
  TrendingUp,
  Undo2,
  UserCog,
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
  reassigned: { icon: UserCog, className: "text-[var(--gold-foreground)]" },
  delegated: { icon: Forward, className: "text-[var(--gold-foreground)]" },
  escalated: { icon: TrendingUp, className: "text-[var(--rust)]" },
  reminded: { icon: BellRing, className: "text-muted-foreground" },
  commented: { icon: MessageSquare, className: "text-muted-foreground" },
  info_requested: { icon: MessageCircleQuestion, className: "text-[var(--gold-foreground)]" },
  info_provided: { icon: MessageSquareReply, className: "text-primary" },
  info_declined: { icon: MessageSquareX, className: "text-[var(--rust)]" },
  info_cancelled: { icon: Ban, className: "text-muted-foreground" },
  info_reassigned: { icon: Forward, className: "text-muted-foreground" },
  supporting_document_added: { icon: FilePlus, className: "text-primary" },
  supporting_document_removed: { icon: FileMinus, className: "text-muted-foreground" },
}

function detailText(action: WorkflowActionDto, key: string): string | null {
  const value = action.detail?.[key]
  return typeof value === "string" && value ? value : null
}

/**
 * Person-performed actions read "{actor} {label}"; system events (null
 * actor) read as the label on its own. Phase 2 entries add who it was
 * delegated to / escalated to / asked, from the action's detail - e.g.
 * "Delegate1 approved, delegated from Nimal" (decision 2).
 */
function describe(action: WorkflowActionDto) {
  const label = ACTION_TYPE_LABELS[action.actionType] ?? action.actionType
  const actor = action.actor ? action.actor.displayName ?? "Someone" : null
  switch (action.actionType) {
    case "approved":
    case "rejected": {
      const from = detailText(action, "delegatedFromName")
      const route = detailText(action, "route")
      if (from) return `${actor} ${label}, delegated from ${from}`
      if (route === "escalated") return `${actor} ${label} (escalated to them)`
      break
    }
    case "delegated":
    case "reassigned": {
      const to = detailText(action, "toName")
      if (to) return `${actor} ${action.actionType === "delegated" ? "delegated" : "reassigned"} the task to ${to}`
      break
    }
    case "escalated": {
      const target = detailText(action, "targetName")
      return target ? `Overdue - escalated to ${target}` : "Overdue - escalation skipped (no usable target)"
    }
    case "info_requested": {
      const to = detailText(action, "requestedFromName")
      if (to) return `${actor} asked ${to} for information`
      break
    }
    case "info_cancelled":
      if (!actor) return "Information request cancelled"
      break
    case "info_reassigned": {
      const to = detailText(action, "toName")
      if (to) return `${actor} redirected an information request to ${to}`
      break
    }
    default:
      break
  }
  return actor ? `${actor} ${label}` : label
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
