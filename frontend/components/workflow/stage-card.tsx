"use client"

import { useState } from "react"
import { AlertTriangle, ArrowDown, ArrowUp, BellRing, Clock, MessageSquareMore, PenLine, Pencil, Trash2, TrendingUp } from "lucide-react"

import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { SearchableSelect } from "@/components/ui/searchable-select"
import { SegmentedControl } from "@/components/ui/segmented-control"
import { Switch } from "@/components/ui/switch"
import { useUserOptions } from "@/lib/workflow/use-participant-options"
import { COMPLETION_RULE_LABELS, REJECTION_RULE_LABELS } from "@/lib/workflow/workflow-badges"
import { cn } from "@/lib/utils"

import type { UpsertStagePayload, WorkflowStageDto } from "@/lib/workflow/api"

import { ASSIGNEE_TYPE_ICON, ParticipantPicker } from "./participant-picker"
import { CollapsibleSection, HoursInput, HowRemindersWork, InheritableHours } from "./reminder-fields"
import { buildStageSchema, type StageFormValues, type TemplateReminderDefaults } from "./schema"

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

const ESCALATION_OPTIONS = [
  { value: "none" as const, label: "None" },
  { value: "department_head" as const, label: "Department head" },
  { value: "named_user" as const, label: "Named person" },
]

const ESCALATION_HELP = {
  none: "No escalation target: nothing happens at the due time and the task just shows as overdue.",
  department_head:
    "A department pool task goes to that department's head; a user or role task to the head of the submitter's department. The original approvers can still act.",
  named_user:
    "This person can also approve or reject once the task escalates (at the due time, or after the inactivity reminder). The original approvers can still act.",
}

const ICON_BUTTON =
  "flex size-8 items-center justify-center rounded-btn border border-[var(--border-strong)] bg-card text-muted-foreground hover:text-foreground disabled:cursor-not-allowed disabled:opacity-40"

function toFormValues(stage: WorkflowStageDto | null): StageFormValues {
  return {
    name: stage?.name ?? "",
    completionRule: stage?.completionRule ?? "all",
    rejectionRule: stage?.rejectionRule ?? "halt_immediately",
    slaHours: stage?.slaHours ?? null,
    reminderBeforeHours: stage?.reminderBeforeHours ?? null,
    inactivityReminderHours: stage?.inactivityReminderHours ?? null,
    escalateAfterReminderHours: stage?.escalateAfterReminderHours ?? null,
    escalationTarget: stage?.escalationTarget ?? "none",
    escalationUserId: stage?.escalationUserId ?? null,
    allowInfoRequests: stage?.allowInfoRequests ?? true,
    // Mandatory for every stage (V117) - never actually sent to the backend, which ignores
    // the field now, but kept as a constant true here so the read-only summary (and the form
    // state in general) doesn't have to special-case a stage that hasn't been saved yet.
    requiresSignature: true,
    participants: (stage?.participants ?? [])
      .slice()
      .sort((a, b) => a.displayOrder - b.displayOrder)
      .map((p) => ({ assigneeType: p.assigneeType, assigneeId: p.assigneeId, displayName: p.displayName })),
  }
}

function toPayload(values: StageFormValues): UpsertStagePayload {
  return {
    name: values.name.trim(),
    completionRule: values.completionRule,
    rejectionRule: values.rejectionRule,
    slaHours: values.slaHours,
    reminderBeforeHours: values.reminderBeforeHours,
    inactivityReminderHours: values.inactivityReminderHours,
    escalateAfterReminderHours: values.escalateAfterReminderHours,
    escalationTarget: values.escalationTarget,
    escalationUserId: values.escalationTarget === "named_user" ? values.escalationUserId : null,
    allowInfoRequests: values.allowInfoRequests,
    participants: values.participants.map((p, index) => ({
      assigneeType: p.assigneeType,
      assigneeId: p.assigneeId,
      displayOrder: index + 1,
    })),
  }
}

/**
 * What the stage will actually use. The due-date reminder and escalation target are the stage's own; the two
 * inactivity hours fall back to the template default (mirrors the backend's resolution).
 */
function effectiveReminders(
  stage: Pick<
    StageFormValues,
    "reminderBeforeHours" | "inactivityReminderHours" | "escalateAfterReminderHours" | "escalationTarget"
  >,
  defaults: TemplateReminderDefaults | null
) {
  return {
    reminderBefore: stage.reminderBeforeHours,
    inactivity: stage.inactivityReminderHours ?? defaults?.inactivityReminderHours ?? null,
    escalateAfter: stage.escalateAfterReminderHours ?? defaults?.escalateAfterReminderHours ?? null,
    target: stage.escalationTarget,
  }
}

/** One line for the collapsed "Reminders & escalation" section, e.g. "Reminder 24h before due · Inactivity 72h · Escalates to Department head". */
function remindersSummary(effective: ReturnType<typeof effectiveReminders>): string {
  const parts = [
    effective.reminderBefore != null ? `Reminder ${effective.reminderBefore}h before due` : null,
    effective.inactivity != null ? `Inactivity ${effective.inactivity}h` : null,
    effective.target === "department_head" ? "Escalates to Department head" : null,
    effective.target === "named_user" ? "Escalates to a named person" : null,
  ].filter(Boolean)
  return parts.length > 0 ? parts.join(" · ") : "None set"
}

interface StageCardProps {
  /** null for a new, not-yet-saved stage (which starts in edit mode). */
  stage: WorkflowStageDto | null
  position: number
  total: number
  /** False once the template is no longer a draft - the card is then read-only. */
  editable: boolean
  busy: boolean
  /** What an inactivity field left empty on this stage inherits - the template's defaults. */
  templateDefaults?: TemplateReminderDefaults | null
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
export function StageCard({ stage, position, total, editable, busy, templateDefaults = null, onSave, onDelete, onMoveUp, onMoveDown, onCancelNew }: StageCardProps) {
  const isNew = stage === null
  const [draft, setDraft] = useState<StageFormValues | null>(() => (isNew ? toFormValues(null) : null))
  const [errors, setErrors] = useState<Partial<Record<keyof StageFormValues, string>>>({})
  const [remindersOpen, setRemindersOpen] = useState(false)

  function update(patch: Partial<StageFormValues>) {
    setDraft((current) => (current ? { ...current, ...patch } : current))
  }

  async function handleSave() {
    if (!draft) return
    const parsed = buildStageSchema(templateDefaults).safeParse(draft)
    if (!parsed.success) {
      const next: Partial<Record<keyof StageFormValues, string>> = {}
      for (const issue of parsed.error.issues) {
        const key = issue.path[0] as keyof StageFormValues
        if (!next[key]) next[key] = issue.message
      }
      setErrors(next)
      setRemindersOpen(true)   // an error in the collapsed section would be invisible
      return
    }
    setErrors({})
    const ok = await onSave(toPayload(parsed.data))
    if (ok && !isNew) {
      setDraft(null)
      setRemindersOpen(false)
    }
  }

  function handleCancel() {
    setErrors({})
    setRemindersOpen(false)
    if (isNew) onCancelNew?.()
    else setDraft(null)
  }

  const users = useUserOptions(Boolean(draft) && draft?.escalationTarget === "named_user")
  const values = draft ?? toFormValues(stage)
  const effective = effectiveReminders(values, templateDefaults)
  // The template's escalate-after is inherited, but with no "Escalate to" on this stage it can never fire.
  const inertTemplateEscalateAfter =
    values.escalateAfterReminderHours == null &&
    templateDefaults?.escalateAfterReminderHours != null &&
    values.escalationTarget === "none"
      ? templateDefaults.escalateAfterReminderHours
      : null
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
              {effective.reminderBefore ? ` · Reminder ${effective.reminderBefore}h before` : ""}
              {effective.inactivity ? ` · Inactivity reminder ${effective.inactivity}h` : ""}
              {effective.target === "department_head" ? " · Escalates to department head" : ""}
              {effective.target === "named_user" ? " · Escalates to a named person" : ""}
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

          <label className="flex items-start gap-3">
            <Switch
              checked={draft.allowInfoRequests}
              onCheckedChange={(allowInfoRequests) => update({ allowInfoRequests })}
              className="mt-0.5"
            />
            <span className="text-[13px] text-foreground">
              Allow information requests
              <span className="block text-[12px] text-muted-foreground">
                Approvers can pause their task to ask someone for a document or an answer; the SLA clock stops meanwhile.
              </span>
            </span>
          </label>

          <p className="flex items-start gap-2 text-[12.5px] text-muted-foreground">
            <PenLine size={14} className="mt-0.5 shrink-0" aria-hidden="true" />
            <span>
              <strong className="text-foreground">Requires digital signature.</strong> Every stage does - approving
              needs an explicit signature (password, and an authenticator code if enabled); the plain Approve action
              is always refused.
            </span>
          </p>

          <CollapsibleSection
            title="Reminders & escalation"
            summary={remindersSummary(effective)}
            attention={inertTemplateEscalateAfter != null ? "Check" : undefined}
            open={remindersOpen}
            onOpenChange={setRemindersOpen}
          >
            <p className="text-[12.5px] text-muted-foreground">
              Remind approvers before a task is due or when it sits idle, and escalate if nobody acts. <HowRemindersWork />
            </p>

            <div className="sm:w-[300px]">
              <Label htmlFor={`stage-reminder-${stage?.stageId ?? "new"}`}>
                Due-Date Reminder: Hours Before Due <span className="text-[var(--ink-faint)] normal-case">(optional)</span>
              </Label>
              <HoursInput
                id={`stage-reminder-${stage?.stageId ?? "new"}`}
                max={8759}
                disabled={draft.slaHours == null}
                value={draft.reminderBeforeHours}
                invalid={Boolean(errors.reminderBeforeHours)}
                onChange={(reminderBeforeHours) => update({ reminderBeforeHours })}
              />
              {draft.slaHours == null && <p className="mt-1.5 text-[12px] text-muted-foreground">Set SLA hours first.</p>}
              {errors.reminderBeforeHours && <p className="mt-1.5 text-[12px] text-destructive">{errors.reminderBeforeHours}</p>}
            </div>

            <InheritableHours
              id={`stage-inactivity-${stage?.stageId ?? "new"}`}
              label="Inactivity Reminder: Hours Without Activity"
              value={draft.inactivityReminderHours}
              inheritedValue={templateDefaults?.inactivityReminderHours ?? null}
              onChange={(inactivityReminderHours) => update({ inactivityReminderHours })}
              error={errors.inactivityReminderHours}
            />

            <InheritableHours
              id={`stage-escalate-after-${stage?.stageId ?? "new"}`}
              label="Escalate After The Inactivity Reminder: Hours"
              value={draft.escalateAfterReminderHours}
              inheritedValue={templateDefaults?.escalateAfterReminderHours ?? null}
              onChange={(escalateAfterReminderHours) => update({ escalateAfterReminderHours })}
              error={errors.escalateAfterReminderHours}
              help="Needs an inactivity reminder and an escalation target."
            />

            <div>
              <Label>Escalate To</Label>
              <SegmentedControl
                value={draft.escalationTarget}
                onChange={(escalationTarget) => update({ escalationTarget })}
                options={ESCALATION_OPTIONS}
              />
              <p className="mt-2 text-[12.5px] text-muted-foreground">{ESCALATION_HELP[draft.escalationTarget]}</p>
              {inertTemplateEscalateAfter != null && (
                <p className="mt-2 flex items-start gap-1.5 rounded-btn border border-[var(--gold)] bg-[var(--gold-tint)] px-3 py-2 text-[12.5px] text-[var(--gold-foreground)]">
                  <AlertTriangle size={14} className="mt-0.5 shrink-0" />
                  The template&apos;s escalate-after ({inertTemplateEscalateAfter} h) has no effect on this stage because no
                  &quot;Escalate to&quot; is set. Choose who to escalate to, or leave it as is if this stage should not escalate.
                </p>
              )}
              {errors.escalationTarget && <p className="mt-1.5 text-[12px] text-destructive">{errors.escalationTarget}</p>}
              {draft.escalationTarget === "named_user" && (
                <div className="mt-3 sm:w-[320px]">
                  <SearchableSelect
                    options={(users.data ?? []).map((u) => ({ value: u.value, label: u.hint ? `${u.label} (${u.hint})` : u.label }))}
                    selected={draft.escalationUserId ?? ""}
                    onChange={(value) => update({ escalationUserId: value || null })}
                    allLabel={users.isPending ? "Loading people…" : "Choose a person"}
                    searchPlaceholder="Search people"
                  />
                  {errors.escalationUserId && <p className="mt-1.5 text-[12px] text-destructive">{errors.escalationUserId}</p>}
                </div>
              )}
            </div>
          </CollapsibleSection>

          <div className="flex items-center justify-end gap-2.5 border-t border-dashed border-border pt-4">
            <button
              type="button"
              onClick={handleCancel}
              disabled={busy}
              className="rounded-btn border border-[var(--border-strong)] bg-card font-semibold text-foreground disabled:opacity-60 h-[38px] px-4 text-[13px] inline-flex items-center justify-center"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={handleSave}
              disabled={busy}
              className="border border-primary bg-primary text-primary-foreground disabled:cursor-not-allowed disabled:opacity-60 h-[38px] rounded-btn px-4 text-[13px] font-semibold inline-flex items-center justify-center"
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
            {effective.reminderBefore != null && stage.slaHours != null && (
              <p className="mt-1 flex items-center gap-1.5 text-[12px] text-muted-foreground">
                <BellRing size={12} /> Approvers are reminded {effective.reminderBefore} hours before it is due.
              </p>
            )}
            {effective.inactivity != null && (
              <p className="mt-1 flex items-center gap-1.5 text-[12px] text-muted-foreground">
                <BellRing size={12} /> Approvers are reminded after {effective.inactivity} hours without activity
                {stage.inactivityReminderHours == null ? " (template default)." : "."}
              </p>
            )}
            {effective.target !== "none" && (
              <p className="mt-1 flex items-center gap-1.5 text-[12px] text-muted-foreground">
                <TrendingUp size={12} /> Escalates to{" "}
                {effective.target === "department_head" ? "the department head" : "a named person"}
                {stage.slaHours != null ? " when overdue" : ""}
                {effective.inactivity != null && effective.escalateAfter != null
                  ? `${stage.slaHours != null ? ", or" : " "} ${effective.escalateAfter} hours after the inactivity reminder${
                      stage.escalateAfterReminderHours == null ? " (template default)" : ""
                    }`
                  : ""}
                {"."}
              </p>
            )}
            {!stage.allowInfoRequests && (
              <p className="mt-1 flex items-center gap-1.5 text-[12px] text-muted-foreground">
                <MessageSquareMore size={12} /> Information requests are switched off for this stage.
              </p>
            )}
            <p className="mt-1 flex items-center gap-1.5 text-[12px] text-muted-foreground">
              <PenLine size={12} /> Approving this stage requires a digital signature.
            </p>
          </div>
        )
      )}
    </div>
  )
}
