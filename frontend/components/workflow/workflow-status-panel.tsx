"use client"

import { useState } from "react"
import Link from "next/link"
import {
  AlertTriangle,
  Ban,
  Check,
  CheckCircle2,
  Clock,
  Forward,
  Hand,
  MessageCircleQuestion,
  PauseCircle,
  TrendingUp,
  UserCog,
  X,
  XCircle,
} from "lucide-react"

import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogDescription,
  AlertDialogPopup,
  AlertDialogPortal,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { Badge } from "@/components/ui/badge"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { useCurrentUser } from "@/lib/auth/use-current-user"
import { useIsTenantAdmin } from "@/lib/auth/use-is-tenant-admin"
import { usePermissions } from "@/lib/auth/use-permissions"
import { useWorkflowActions } from "@/lib/workflow/use-workflow-actions"
import {
  COMPLETION_RULE_LABELS,
  INSTANCE_STATUS_BADGE_VARIANT,
  INSTANCE_STATUS_LABELS,
  REJECTION_RULE_LABELS,
  STAGE_STATUS_BADGE_VARIANT,
  STAGE_STATUS_LABELS,
  TASK_STATUS_BADGE_VARIANT,
  TASK_STATUS_LABELS,
} from "@/lib/workflow/workflow-badges"
import { cn, formatDateTime } from "@/lib/utils"

import type { InfoRequestDto, WorkflowInstanceDto, WorkflowStageInstanceDto, WorkflowTaskDto } from "@/lib/workflow/api"

import { SignAndApproveDialog } from "@/components/signatures/sign-and-approve-dialog"

import { DelegateTaskDialog } from "./delegate-task-dialog"
import { InfoRequestsList } from "./info-requests-list"
import { RequestInfoDialog } from "./request-info-dialog"
import { SupportingDocumentsList } from "./supporting-documents-list"
import { TaskActionDialog, type TaskDecisionKind } from "./task-action-dialog"

const CANCEL_REASON_MAX = 2000

interface WorkflowStatusPanelProps {
  instance: WorkflowInstanceDto
  /** Hides the "open document" link when already on the document page. */
  showDocumentLink?: boolean
}

/**
 * One workflow run's status (WA-FR-005): outcome, a vertical stage stepper
 * (each stage's status, approvals so far against what its rule requires,
 * due date and every task with its assignee, decision and reason), the
 * caller's own pending task with its actions, and "Cancel workflow" for the
 * initiator or a tenant admin (a reason is required). Phase 2 adds, per
 * task, who it was delegated or escalated to and whom it is waiting on;
 * Delegate and Request information on my task; Reassign for admins; and
 * the run's information requests and supporting documents.
 */
export function WorkflowStatusPanel({ instance, showDocumentLink = true }: WorkflowStatusPanelProps) {
  const { data: currentUser } = useCurrentUser()
  const { isTenantAdmin } = useIsTenantAdmin()
  const { hasPermission } = usePermissions()
  const actions = useWorkflowActions()
  const [decision, setDecision] = useState<TaskDecisionKind | null>(null)
  const [signing, setSigning] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const [moving, setMoving] = useState<{ task: WorkflowTaskDto; stage: WorkflowStageInstanceDto; mode: "delegate" | "reassign" } | null>(null)
  const [requestingInfo, setRequestingInfo] = useState(false)

  const inProgress = instance.status === "in_progress"
  const isInitiator = Boolean(currentUser?.userId) && currentUser?.userId === instance.initiatedBy?.userId
  const canCancel = inProgress && (isTenantAdmin || (isInitiator && hasPermission(PermissionCodes.DLM_SUBMIT_DOCUMENTS_FOR_REVIEW)))

  const stages = instance.stages.slice().sort((a, b) => a.stageOrder - b.stageOrder)
  const myTask = findTask(stages, instance.myOpenTaskId)
  const myStage = myTask ? stages.find((s) => s.tasks.some((t) => t.taskId === myTask.taskId)) : undefined
  // Claiming is optional: deciding an unclaimed pool task claims it implicitly.
  const myTaskClaimable = myTask ? myTask.assigneeType !== "user" && myTask.status === "pending" && !myTask.delegatedTo : false
  const myTaskWaiting = myTask?.status === "awaiting_info"
  const me = currentUser?.userId
  const iHoldMyTask = Boolean(myTask && me && holderOf(myTask) === me)
  const canApprove = hasPermission(PermissionCodes.WA_TASK_APPROVE)
  const canReject = hasPermission(PermissionCodes.WA_TASK_REJECT)
  const canRequestInfo = Boolean(myStage?.allowInfoRequests) && hasPermission(PermissionCodes.WA_REQUEST_INFORMATION)
  const canReassign = inProgress && hasPermission(PermissionCodes.WA_TASK_REASSIGN)
  const askedOfMe = instance.myOpenInfoRequestIds ?? []
  const infoRequests = instance.infoRequests ?? []
  const waitingOn = openRequestsByTask(infoRequests)
  const canAttach =
    hasPermission(PermissionCodes.DOCM_DOCUMENTS_VIEW) && (isInitiator || Boolean(myTask) || askedOfMe.length > 0)

  return (
    <div className="rounded-lg border border-border bg-card shadow-[var(--ledger-shadow)]">
      <div className="flex flex-wrap items-start justify-between gap-3 border-b border-border px-5 py-4">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <h2 className="font-heading text-[16.5px] font-semibold">
              {instance.templateName} <span className="text-[13px] font-normal text-muted-foreground">v{instance.templateVersionNumber}</span>
            </h2>
            <Badge variant={INSTANCE_STATUS_BADGE_VARIANT[instance.status]} className="gap-1.5 rounded-md">
              <span className="size-[5px] rounded-full bg-current" />
              {INSTANCE_STATUS_LABELS[instance.status]}
            </Badge>
          </div>
          <p className="mt-1 text-[12.5px] text-muted-foreground">
            Submitted by {instance.initiatedBy?.displayName ?? "—"} · {formatDateTime(instance.startedAt)}
            {instance.completedAt && <> · Finished {formatDateTime(instance.completedAt)}</>}
            {showDocumentLink && (
              <>
                {" · "}
                <Link href={`/documents/${instance.documentId}`} className="font-semibold text-primary hover:underline">
                  {instance.documentTitle ?? "Open document"}
                </Link>
              </>
            )}
          </p>
        </div>
        {canCancel && (
          <button
            type="button"
            onClick={() => setCancelling(true)}
            disabled={actions.isActing}
            className="flex items-center gap-1.5 rounded-btn border border-[var(--border-strong)] bg-card px-3.5 py-2 text-[13px] font-semibold text-muted-foreground hover:border-[var(--rust)] hover:text-[var(--rust)] disabled:opacity-60"
          >
            <Ban size={14} /> Cancel Workflow
          </button>
        )}
      </div>

      <div className="flex flex-col gap-3 px-5 py-4">
        {instance.versionChangedFlag && (
          <div className="flex items-start gap-2 rounded-btn border border-[var(--gold)] bg-[var(--gold-tint)] px-3.5 py-2.5 text-[12.5px] text-[var(--gold-foreground)]">
            <AlertTriangle size={14} className="mt-0.5 shrink-0" />
            A newer version of this document was uploaded after it was submitted. Approvers are deciding on the
            submitted version.
          </div>
        )}

        {instance.outcomeReason && !inProgress && (
          <div
            className={cn(
              "rounded-btn border px-3.5 py-2.5 text-[13px]",
              instance.status === "approved" ? "border-primary/40 bg-primary/5" : "border-[var(--rust)] bg-[var(--rust-tint)]"
            )}
          >
            <span className="font-semibold">{INSTANCE_STATUS_LABELS[instance.status]}:</span>{" "}
            <span className="whitespace-pre-wrap">{instance.outcomeReason}</span>
          </div>
        )}

        {askedOfMe.length > 0 && inProgress && (
          <div className="flex items-start gap-2 rounded-btn border border-[var(--gold)] bg-[var(--gold-tint)] px-3.5 py-2.5 text-[13px] text-[var(--gold-foreground)]">
            <MessageCircleQuestion size={15} className="mt-0.5 shrink-0" />
            <span>
              <span className="font-semibold">You&apos;ve been asked for information.</span> An approver is waiting on your
              answer - see Information requests below.
            </span>
          </div>
        )}

        {myTask && myStage && inProgress && (
          <div className="flex flex-wrap items-center justify-between gap-3 rounded-btn border border-primary bg-primary/5 px-3.5 py-2.5">
            <p className="text-[13px] text-foreground">
              {myTaskWaiting ? (
                <>
                  <span className="font-semibold">Your task is paused</span> on stage {myStage.stageOrder}: {myStage.name}
                  <span className="text-muted-foreground"> - waiting on {waitingOn.get(myTask.taskId)?.join(", ") ?? "information"}. Its clock is stopped.</span>
                </>
              ) : (
                <>
                  <span className="font-semibold">Your action is needed</span> on stage {myStage.stageOrder}: {myStage.name}
                  {myTaskClaimable && (
                    <span className="text-muted-foreground"> - sent to the {myTask.assigneeName ?? "pool"} pool; the first member to decide answers for it.</span>
                  )}
                  {myTask.delegatedFrom && myTask.delegatedTo?.userId === me && (
                    <span className="text-muted-foreground"> - delegated to you by {myTask.delegatedFrom.displayName ?? "a colleague"}.</span>
                  )}
                  {myTask.escalatedTo?.userId === me && holderOf(myTask) !== me && (
                    <span className="text-muted-foreground"> - escalated to you because it is overdue.</span>
                  )}
                </>
              )}
            </p>
            <div className="flex flex-wrap gap-2">
              {canRequestInfo && (
                <button
                  type="button"
                  disabled={actions.isActing}
                  onClick={() => setRequestingInfo(true)}
                  className="flex items-center gap-1 rounded-sm border border-[var(--border-strong)] bg-card px-3 py-1.5 text-[12.5px] font-semibold text-foreground disabled:opacity-60"
                >
                  <MessageCircleQuestion size={13} /> Request Info
                </button>
              )}
              {iHoldMyTask && !myTaskWaiting && (canApprove || canReject) && (
                <button
                  type="button"
                  disabled={actions.isActing}
                  onClick={() => setMoving({ task: myTask, stage: myStage, mode: "delegate" })}
                  className="flex items-center gap-1 rounded-sm border border-[var(--border-strong)] bg-card px-3 py-1.5 text-[12.5px] font-semibold text-foreground disabled:opacity-60"
                >
                  <Forward size={13} /> Delegate
                </button>
              )}
              {myTaskClaimable && (canApprove || canReject) && (
                <button
                  type="button"
                  disabled={actions.isActing}
                  onClick={() => void actions.claim(myTask.taskId)}
                  className="flex items-center gap-1 rounded-sm border border-primary bg-primary/10 px-3 py-1.5 text-[12.5px] font-semibold text-primary disabled:opacity-60"
                >
                  <Hand size={13} /> Claim
                </button>
              )}
              {canApprove && !myTaskWaiting && (
                <button
                  type="button"
                  disabled={actions.isActing}
                  onClick={() => (myStage?.requiresSignature ? setSigning(true) : setDecision("approve"))}
                  className="flex items-center gap-1 rounded-sm border border-primary bg-primary px-3 py-1.5 text-[12.5px] font-semibold text-primary-foreground disabled:opacity-60"
                >
                  <CheckCircle2 size={13} /> Approve
                </button>
              )}
              {canReject && !myTaskWaiting && (
                <button
                  type="button"
                  disabled={actions.isActing}
                  onClick={() => setDecision("reject")}
                  className="flex items-center gap-1 rounded-sm border border-[var(--rust)] bg-card px-3 py-1.5 text-[12.5px] font-semibold text-[var(--rust)] disabled:opacity-60"
                >
                  <XCircle size={13} /> Reject
                </button>
              )}
            </div>
          </div>
        )}

        <ol className="flex flex-col">
          {stages.map((stage, index) => (
            <StageStep
              key={stage.stageInstanceId}
              stage={stage}
              last={index === stages.length - 1}
              current={stage.stageInstanceId === instance.currentStageInstanceId}
              totalStages={stages.length}
              waitingOn={waitingOn}
              onReassign={canReassign ? (task) => setMoving({ task, stage, mode: "reassign" }) : undefined}
            />
          ))}
        </ol>

        <InfoRequestsList
          requests={infoRequests}
          documentTitle={instance.documentTitle ?? "Untitled document"}
          currentUserId={me}
          canRedirect={isInitiator || isTenantAdmin}
          inProgress={inProgress}
        />
        <SupportingDocumentsList
          instanceId={instance.instanceId}
          documentId={instance.documentId}
          documents={instance.supportingDocuments ?? []}
          currentUserId={me}
          canAttach={canAttach}
          inProgress={inProgress}
        />
      </div>

      {decision && myTask && myStage && (
        <TaskActionDialog
          decision={decision}
          documentTitle={instance.documentTitle ?? "Untitled document"}
          stageLabel={`Stage ${myStage.stageOrder} of ${stages.length}: ${myStage.name}`}
          versionChanged={instance.versionChangedFlag}
          onOpenChange={(open) => !open && setDecision(null)}
          onConfirm={async (text) =>
            (decision === "approve" ? await actions.approve(myTask.taskId, text) : await actions.reject(myTask.taskId, text)) !== null
          }
        />
      )}

      {signing && myTask && myStage && (
        <SignAndApproveDialog
          taskId={myTask.taskId}
          documentTitle={instance.documentTitle ?? "Untitled document"}
          stageLabel={`Stage ${myStage.stageOrder} of ${stages.length}: ${myStage.name}`}
          versionChanged={instance.versionChangedFlag}
          onOpenChange={(open) => !open && setSigning(false)}
        />
      )}

      {moving && (
        <DelegateTaskDialog
          mode={moving.mode}
          taskId={moving.task.taskId}
          documentTitle={instance.documentTitle ?? "Untitled document"}
          stageLabel={`Stage ${moving.stage.stageOrder} of ${stages.length}: ${moving.stage.name}`}
          onOpenChange={(open) => !open && setMoving(null)}
        />
      )}

      {requestingInfo && myTask && (
        <RequestInfoDialog
          mode="raise"
          taskId={myTask.taskId}
          documentTitle={instance.documentTitle ?? "Untitled document"}
          onOpenChange={(open) => !open && setRequestingInfo(false)}
        />
      )}

      {cancelling && (
        <CancelWorkflowDialog
          onOpenChange={(open) => !open && setCancelling(false)}
          onConfirm={async (reason) => (await actions.cancel(instance.instanceId, reason)) !== null}
        />
      )}
    </div>
  )
}

function findTask(stages: WorkflowStageInstanceDto[], taskId: string | null): WorkflowTaskDto | undefined {
  if (!taskId) return undefined
  for (const stage of stages) {
    const task = stage.tasks.find((t) => t.taskId === taskId)
    if (task) return task
  }
  return undefined
}

/** The one person holding a task: its delegate, else its claimer, else its user assignee (null for an unclaimed pool). */
function holderOf(task: WorkflowTaskDto): string | null {
  return task.delegatedTo?.userId ?? task.claimedBy?.userId ?? (task.assigneeType === "user" ? task.assigneeId : null)
}

/** Names each waiting task is waiting on, from the run's open information requests. */
function openRequestsByTask(requests: InfoRequestDto[]): Map<string, string[]> {
  const map = new Map<string, string[]>()
  for (const r of requests) {
    if (r.status !== "open") continue
    map.set(r.taskId, [...(map.get(r.taskId) ?? []), r.requestedFrom.displayName ?? "someone"])
  }
  return map
}

const OPEN_TASK_STATUSES = new Set(["pending", "claimed", "awaiting_info"])

/** Read at render time on purpose: the instance re-polls every 15s while in progress, which refreshes it. */
function isPast(iso: string | null): boolean {
  return iso !== null && new Date(iso).getTime() < new Date().getTime()
}

const STEP_DOT: Record<WorkflowStageInstanceDto["status"], string> = {
  not_started: "border-[var(--border-strong)] bg-card text-muted-foreground",
  active: "border-[var(--gold-foreground)] bg-[var(--gold-tint)] text-[var(--gold-foreground)]",
  approved: "border-primary bg-primary text-primary-foreground",
  rejected: "border-[var(--rust)] bg-[var(--rust)] text-white",
  skipped: "border-[var(--border-strong)] bg-muted text-muted-foreground",
}

interface StageStepProps {
  stage: WorkflowStageInstanceDto
  last: boolean
  current: boolean
  totalStages: number
  waitingOn: Map<string, string[]>
  onReassign?: (task: WorkflowTaskDto) => void
}

function StageStep({ stage, last, current, totalStages, waitingOn, onReassign }: StageStepProps) {
  const progress = stage.requiredCount > 0 ? Math.min(100, Math.round((stage.approvedCount / stage.requiredCount) * 100)) : 0
  const overdue = stage.status === "active" && isPast(stage.dueAt)

  return (
    <li className="relative flex gap-3.5 pb-5 last:pb-0">
      {!last && <span className="absolute top-8 bottom-0 left-[15px] w-px bg-border" aria-hidden="true" />}
      <span className={cn("relative z-[1] flex size-8 shrink-0 items-center justify-center rounded-full border-2 text-[12.5px] font-bold", STEP_DOT[stage.status])}>
        {stage.status === "approved" ? <Check size={15} /> : stage.status === "rejected" ? <X size={15} /> : stage.stageOrder}
      </span>
      <div className={cn("min-w-0 flex-1 rounded-md border px-3.5 py-3", current ? "border-[var(--gold)]" : "border-border")}>
        <div className="flex flex-wrap items-center gap-2">
          <p className="text-[13.5px] font-semibold text-foreground">
            <span className="sr-only">Stage {stage.stageOrder} of {totalStages}: </span>
            {stage.name}
          </p>
          <Badge variant={STAGE_STATUS_BADGE_VARIANT[stage.status]}>{STAGE_STATUS_LABELS[stage.status]}</Badge>
          {overdue && <Badge variant="rust">Overdue</Badge>}
        </div>
        <p className="mt-1 text-[12px] text-muted-foreground">
          {COMPLETION_RULE_LABELS[stage.completionRule]} of {stage.entryCount} {stage.entryCount === 1 ? "entry" : "entries"} ·{" "}
          {REJECTION_RULE_LABELS[stage.rejectionRule]}
        </p>

        {stage.status !== "not_started" && stage.status !== "skipped" && (
          <div className="mt-2">
            <div className="flex items-center justify-between text-[12px] text-muted-foreground">
              <span>
                <span className="font-semibold text-foreground">{stage.approvedCount}</span> of {stage.requiredCount} required approvals
                {stage.rejectedCount > 0 && <span className="text-[var(--rust)]"> · {stage.rejectedCount} rejected</span>}
              </span>
              {stage.dueAt && (
                <span className="flex items-center gap-1">
                  <Clock size={12} /> Due {formatDateTime(stage.dueAt)}
                </span>
              )}
            </div>
            <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-muted" role="progressbar" aria-valuenow={progress} aria-valuemin={0} aria-valuemax={100}>
              <div className={cn("h-full rounded-full", stage.status === "rejected" ? "bg-[var(--rust)]" : "bg-primary")} style={{ width: `${progress}%` }} />
            </div>
          </div>
        )}

        {stage.tasks.length > 0 && (
          <ul className="mt-2.5 divide-y divide-dashed divide-border">
            {stage.tasks.map((task) => (
              <li key={task.taskId} className="py-2 text-[12.5px]">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-medium text-foreground">{task.assigneeName ?? "Unknown"}</span>
                  {task.assigneeType !== "user" && <span className="text-[11.5px] text-muted-foreground">({task.assigneeType} pool)</span>}
                  <Badge variant={TASK_STATUS_BADGE_VARIANT[task.status]}>{TASK_STATUS_LABELS[task.status]}</Badge>
                  {task.claimedBy && task.status === "claimed" && !task.delegatedTo && (
                    <span className="text-muted-foreground">claimed by {task.claimedBy.displayName ?? "someone"}</span>
                  )}
                  {task.delegatedTo && OPEN_TASK_STATUSES.has(task.status) && (
                    <span className="flex items-center gap-1 text-muted-foreground">
                      <Forward size={12} /> with {task.delegatedTo.displayName ?? "someone"}
                      {task.delegatedFrom && <> (delegated from {task.delegatedFrom.displayName ?? "someone"})</>}
                    </span>
                  )}
                  {task.status === "awaiting_info" && (
                    <span className="flex items-center gap-1 text-[var(--gold-foreground)]">
                      <PauseCircle size={12} /> Waiting on {waitingOn.get(task.taskId)?.join(", ") ?? "information"}
                    </span>
                  )}
                  {task.escalatedAt && (
                    <Badge variant="rust" className="gap-1">
                      <TrendingUp /> {task.escalatedTo ? `Escalated to ${task.escalatedTo.displayName ?? "someone"}` : "Overdue - not escalated"}
                    </Badge>
                  )}
                  {onReassign && stage.status === "active" && OPEN_TASK_STATUSES.has(task.status) && (
                    <button
                      type="button"
                      onClick={() => onReassign(task)}
                      className="ml-auto flex items-center gap-1 rounded-sm border border-[var(--border-strong)] bg-card px-2 py-0.5 text-[11.5px] font-semibold text-muted-foreground hover:text-foreground"
                    >
                      <UserCog size={12} /> Reassign
                    </button>
                  )}
                  {task.actedBy && task.decision && (
                    <span className="text-muted-foreground">
                      {task.decision === "approve" ? "approved" : "rejected"} by {task.actedBy.displayName ?? "someone"}
                      {task.delegatedFrom && <>, delegated from {task.delegatedFrom.displayName ?? "someone"}</>}
                      {task.actedAt && <> · {formatDateTime(task.actedAt)}</>}
                    </span>
                  )}
                </div>
                {OPEN_TASK_STATUSES.has(task.status) && task.dueAt && task.dueAt !== stage.dueAt && (
                  <p className="mt-0.5 flex items-center gap-1 text-[11.5px] text-muted-foreground">
                    <Clock size={11} /> Due {formatDateTime(task.dueAt)}
                    {task.slaPausedSeconds > 0 && " (extended by the time it waited for information)"}
                  </p>
                )}
                {task.decisionReason && (
                  <p className="mt-1 rounded-sm border-l-2 border-[var(--border-strong)] bg-muted px-2.5 py-1.5 whitespace-pre-wrap text-foreground">
                    {task.decisionReason}
                  </p>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>
    </li>
  )
}

/** Cancel needs a reason (the backend 400s a blank one); it's recorded on the timeline and as the outcome. */
function CancelWorkflowDialog({ onOpenChange, onConfirm }: { onOpenChange: (open: boolean) => void; onConfirm: (reason: string) => Promise<boolean> }) {
  const [reason, setReason] = useState("")
  const [submitting, setSubmitting] = useState(false)
  const trimmed = reason.trim()

  async function handleConfirm() {
    setSubmitting(true)
    const ok = await onConfirm(trimmed)
    setSubmitting(false)
    if (ok) onOpenChange(false)
  }

  return (
    <AlertDialog open onOpenChange={onOpenChange}>
      <AlertDialogPortal>
        <AlertDialogPopup className="sm:max-w-xl">
          <span className="mx-auto mb-4 flex size-11 items-center justify-center rounded-md bg-[var(--rust-tint)] text-[var(--rust)]">
            <Ban size={20} aria-hidden="true" />
          </span>
          <AlertDialogTitle>Cancel this workflow?</AlertDialogTitle>
          <AlertDialogDescription>
            Every open approval task is withdrawn and the workflow ends as cancelled. The document can be submitted
            again later.
          </AlertDialogDescription>
          <div className="mb-5 text-left">
            <Label htmlFor="cancel-workflow-reason">Reason (required)</Label>
            <Textarea
              id="cancel-workflow-reason"
              rows={3}
              maxLength={CANCEL_REASON_MAX}
              placeholder="e.g. Submitted the wrong draft…"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
            />
          </div>
          <div className="flex items-center justify-center gap-2.5">
            <AlertDialogClose className="rounded-md border border-[var(--border-strong)] bg-card px-5 py-2.5 text-[13px] font-semibold text-foreground">
              Keep Workflow
            </AlertDialogClose>
            <button
              type="button"
              onClick={() => void handleConfirm()}
              disabled={trimmed.length === 0 || submitting}
              className="rounded-md border border-[var(--rust)] bg-[var(--rust)] px-5 py-2.5 text-[13px] font-bold tracking-[0.02em] text-white disabled:cursor-not-allowed disabled:opacity-50"
            >
              Cancel Workflow
            </button>
          </div>
        </AlertDialogPopup>
      </AlertDialogPortal>
    </AlertDialog>
  )
}
