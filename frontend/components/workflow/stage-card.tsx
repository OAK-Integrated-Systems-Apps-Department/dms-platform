"use client"

import { useState } from "react"
import { AlertTriangle, ArrowDown, ArrowUp, Clock, Pencil, Trash2 } from "lucide-react"

import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { SegmentedControl } from "@/components/ui/segmented-control"
import { COMPLETION_RULE_LABELS, REJECTION_RULE_LABELS } from "@/lib/workflow/workflow-badges"
import { cn } from "@/lib/utils"

import type { UpsertStagePayload, WorkflowStageDto } from "@/lib/workflow/api"

import { ASSIGNEE_TYPE_ICON, ParticipantPicker } from "./participant-picker"
import { stageSchema, type StageFormValues } from "./schema"

const COMPLETION_OPTIONS = [
  { value: "all" as const, label: "All" },
  { value: "any" as const, label: "Any" },
  { value: "majority" as const, label: "Majority" },
]

const COMPLETION_HELP = {
  all: "Every entry must approve.",
  any: "One approval from any entry completes the stage.",
  majority: "More than half of the entries must approve.",
}

const REJECTION_OPTIONS = [
  { value: "halt_immediately" as const, label: REJECTION_RULE_LABELS.halt_immediately },
  { value: "wait_for_all" as const, label: REJECTION_RULE_LABELS.wait_for_all },
]

const REJECTION_HELP = {
  halt_immediately: "The first rejection rejects the stage (and the workflow) straight away.",
  wait_for_all: "Every approver decides first; the stage is rejected only if the approvals can no longer reach the rule.",
}

const ICON_BUTTON =
  "flex size-8 items-center justify-center rounded-btn border border-[var(--border-strong)] bg-card text-muted-foreground hover:text-foreground disabled:cursor-not-allowed disabled:opacity-40"

function toFormValues(stage: WorkflowStageDto | null): StageFormValues {
  return {
    name: stage?.name ?? "",
    completionRule: stage?.completionRule ?? "all",
    rejectionRule: stage?.rejectionRule ?? "halt_immediately",
    slaHours: stage?.slaHours ?? null,
    participants: (stage?.participants ?? [])
      .slice()
      .sort((a, b) => a.displayOrder - b.displayOrder)
      .map((p) => ({ assigneeType: p.assigneeType, assigneeId: p.assigneeId, displayName: p.displayName })),
  }
}

/** Keeps the Phase 2 reminder/escalation fields the card doesn't edit, so saving never clears them. */
function toPayload(values: StageFormValues, stage: WorkflowStageDto | null): UpsertStagePayload {
  return {
    name: values.name.trim(),
    completionRule: values.completionRule,
    rejectionRule: values.rejectionRule,
    slaHours: values.slaHours,
    reminderBeforeHours: stage?.reminderBeforeHours ?? null,
    escalationTarget: stage?.escalationTarget ?? null,
    escalationUserId: stage?.escalationUserId ?? null,
    participants: values.participants.map((p, index) => ({
      assigneeType: p.assigneeType,
      assigneeId: p.assigneeId,
      displayOrder: index + 1,
    })),
  }
}

interface StageCardProps {
  /** null for a new, not-yet-saved stage (which starts in edit mode). */
  stage: WorkflowStageDto | null
  position: number
  total: number
  /** False once the template is no longer a draft - the card is then read-only. */
  editable: boolean
  busy: boolean
  onSave: (payload: UpsertStagePayload) => Promise<boolean>
  onDelete?: () => void
  onMoveUp?: () => void
  onMoveDown?: () => void
  /** New stage only: discard it without saving. */
  onCancelNew?: () => void
}

/**
 * One stage of the template. Read-only summary until "Edit" is clicked;
 * edits live in local state (a fresh copy of the stage each time editing
 * starts) and are sent as one PUT on Save, replacing the whole participant
 * list - so another card saving meanwhile can never clobber them.
 */
export function StageCard({ stage, position, total, editable, busy, onSave, onDelete, onMoveUp, onMoveDown, onCancelNew }: StageCardProps) {
  const isNew = stage === null
  const [draft, setDraft] = useState<StageFormValues | null>(() => (isNew ? toFormValues(null) : null))
  const [errors, setErrors] = useState<Partial<Record<keyof StageFormValues, string>>>({})

  function update(patch: Partial<StageFormValues>) {
    setDraft((current) => (current ? { ...current, ...patch } : current))
  }

  async function handleSave() {
    if (!draft) return
    const parsed = stageSchema.safeParse(draft)
    if (!parsed.success) {
      const next: Partial<Record<keyof StageFormValues, string>> = {}
      for (const issue of parsed.error.issues) {
        const key = issue.path[0] as keyof StageFormValues
        if (!next[key]) next[key] = issue.message
      }
      setErrors(next)
      return
    }
    setErrors({})
    const ok = await onSave(toPayload(parsed.data, stage))
    if (ok && !isNew) setDraft(null)
  }

  function handleCancel() {
    setErrors({})
    if (isNew) onCancelNew?.()
    else setDraft(null)
  }

  const values = draft ?? toFormValues(stage)
  const entries = values.participants.length
  const showMajorityWarning = values.completionRule === "majority" && entries > 0 && entries <= 2

  return (
    <div className={cn("rounded-lg border bg-card shadow-[var(--ledger-shadow)]", draft ? "border-primary" : "border-border")}>
      <div className="flex items-center gap-3 border-b border-border px-5 py-3">
        <span className="flex size-7 shrink-0 items-center justify-center rounded-full bg-primary/10 text-[12.5px] font-bold text-primary">
          {position}
        </span>
        <div className="min-w-0 flex-1">
          <p className="truncate text-[14px] font-semibold text-foreground">
            {isNew ? "New stage" : stage.name}
          </p>
          {!draft && stage && (
            <p className="text-[12px] text-muted-foreground">
              {COMPLETION_RULE_LABELS[stage.completionRule]} must approve · {REJECTION_RULE_LABELS[stage.rejectionRule]}
              {stage.slaHours ? ` · SLA ${stage.slaHours}h` : ""}
            </p>
          )}
        </div>
        {editable && !isNew && !draft && (
          <div className="flex items-center gap-1.5">
            <button type="button" className={ICON_BUTTON} onClick={onMoveUp} disabled={busy || position === 1} aria-label="Move stage up">
              <ArrowUp size={15} />
            </button>
            <button type="button" className={ICON_BUTTON} onClick={onMoveDown} disabled={busy || position === total} aria-label="Move stage down">
              <ArrowDown size={15} />
            </button>
            <button type="button" className={ICON_BUTTON} onClick={() => setDraft(toFormValues(stage))} disabled={busy} aria-label="Edit stage">
              <Pencil size={14} />
            </button>
            <button
              type="button"
              className={cn(ICON_BUTTON, "hover:border-[var(--rust)] hover:text-[var(--rust)]")}
              onClick={onDelete}
              disabled={busy}
              aria-label="Delete stage"
            >
              <Trash2 size={14} />
            </button>
          </div>
        )}
      </div>

      {draft ? (
        <div className="flex flex-col gap-5 px-5 py-4">
          <div className="flex flex-col gap-5 sm:flex-row sm:items-start">
            <div className="flex-1">
              <Label htmlFor={`stage-name-${stage?.stageId ?? "new"}`}>Stage Name</Label>
              <Input
                id={`stage-name-${stage?.stageId ?? "new"}`}
                value={draft.name}
                placeholder="e.g. Legal review"
                aria-invalid={Boolean(errors.name)}
                onChange={(e) => update({ name: e.target.value })}
              />
              {errors.name && <p className="mt-1.5 text-[12px] text-destructive">{errors.name}</p>}
            </div>
            <div className="sm:w-[180px] sm:shrink-0">
              <Label htmlFor={`stage-sla-${stage?.stageId ?? "new"}`}>
                SLA Hours <span className="text-[var(--ink-faint)] normal-case">(optional)</span>
              </Label>
              <Input
                id={`stage-sla-${stage?.stageId ?? "new"}`}
                type="number"
                min={1}
                max={8760}
                value={draft.slaHours ?? ""}
                aria-invalid={Boolean(errors.slaHours)}
                onChange={(e) => update({ slaHours: e.target.value === "" ? null : Number(e.target.value) })}
              />
              {errors.slaHours && <p className="mt-1.5 text-[12px] text-destructive">{errors.slaHours}</p>}
            </div>
          </div>

          <div>
            <Label>Completion Rule</Label>
            <SegmentedControl value={draft.completionRule} onChange={(completionRule) => update({ completionRule })} options={COMPLETION_OPTIONS} />
            <p className="mt-2 text-[12.5px] text-muted-foreground">{COMPLETION_HELP[draft.completionRule]}</p>
            {showMajorityWarning && (
              <p className="mt-2 flex items-start gap-1.5 rounded-btn border border-[var(--gold)] bg-[var(--gold-tint)] px-3 py-2 text-[12.5px] text-[var(--gold-foreground)]">
                <AlertTriangle size={14} className="mt-0.5 shrink-0" />
                Majority = more than half of the entries; with 1-2 entries it means all.
              </p>
            )}
          </div>

          <div>
            <Label>On Rejection</Label>
            <SegmentedControl value={draft.rejectionRule} onChange={(rejectionRule) => update({ rejectionRule })} options={REJECTION_OPTIONS} />
            <p className="mt-2 text-[12.5px] text-muted-foreground">{REJECTION_HELP[draft.rejectionRule]}</p>
          </div>

          <div>
            <Label>Participants</Label>
            <ParticipantPicker value={draft.participants} onChange={(participants) => update({ participants })} />
            {errors.participants && <p className="mt-1.5 text-[12px] text-destructive">{errors.participants}</p>}
          </div>

          <div className="flex items-center justify-end gap-2.5 border-t border-dashed border-border pt-4">
            <button
              type="button"
              onClick={handleCancel}
              disabled={busy}
              className="rounded-btn border border-[var(--border-strong)] bg-card px-4 py-2 text-[13px] font-semibold text-foreground disabled:opacity-60"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={handleSave}
              disabled={busy}
              className="rounded-btn border border-primary bg-primary px-[18px] py-2 text-[13px] font-bold text-primary-foreground disabled:cursor-not-allowed disabled:opacity-60"
            >
              {isNew ? "Add Stage" : "Save Stage"}
            </button>
          </div>
        </div>
      ) : (
        stage && (
          <div className="px-5 py-3.5">
            {stage.participants.length === 0 ? (
              <p className="flex items-center gap-1.5 text-[12.5px] text-[var(--rust)]">
                <AlertTriangle size={13} /> No participants - this stage can&apos;t be completed.
              </p>
            ) : (
              <ul className="flex flex-wrap gap-2">
                {stage.participants
                  .slice()
                  .sort((a, b) => a.displayOrder - b.displayOrder)
                  .map((p) => {
                    const Icon = ASSIGNEE_TYPE_ICON[p.assigneeType]
                    return (
                      <li
                        key={p.participantId}
                        className={cn(
                          "flex items-center gap-1.5 rounded-btn border px-2.5 py-1 text-[12.5px]",
                          p.displayName ? "border-border bg-muted" : "border-[var(--rust)] bg-[var(--rust-tint)] text-[var(--rust)]"
                        )}
                      >
                        <Icon size={13} className="text-muted-foreground" aria-hidden="true" />
                        {p.displayName ?? "Unknown (deleted)"}
                        {p.assigneeType !== "user" && <span className="text-[11px] text-muted-foreground">(pool)</span>}
                      </li>
                    )
                  })}
              </ul>
            )}
            {stage.completionRule === "majority" && stage.participants.length > 0 && stage.participants.length <= 2 && (
              <p className="mt-2 flex items-center gap-1.5 text-[12px] text-[var(--gold-foreground)]">
                <AlertTriangle size={12} /> Majority with {stage.participants.length} {stage.participants.length === 1 ? "entry" : "entries"} means all must approve.
              </p>
            )}
            {stage.slaHours && (
              <p className="mt-2 flex items-center gap-1.5 text-[12px] text-muted-foreground">
                <Clock size={12} /> Due {stage.slaHours} hours after the stage starts.
              </p>
            )}
          </div>
        )
      )}
    </div>
  )
}
