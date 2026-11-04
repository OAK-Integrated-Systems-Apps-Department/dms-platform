"use client"

import { useState } from "react"
import { Plus, Trash2 } from "lucide-react"

import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogDescription,
  AlertDialogPopup,
  AlertDialogPortal,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"

import type { UpsertStagePayload, WorkflowStageDto, WorkflowTemplateDto } from "@/lib/workflow/api"

import { StageCard } from "./stage-card"
import type { TemplateReminderDefaults } from "./schema"

interface StageBuilderProps {
  template: WorkflowTemplateDto
  editable: boolean
  busy: boolean
  onAdd: (payload: UpsertStagePayload) => Promise<WorkflowTemplateDto | null>
  onUpdate: (stageId: string, payload: UpsertStagePayload) => Promise<WorkflowTemplateDto | null>
  onDelete: (stageId: string) => Promise<WorkflowTemplateDto | null>
  onReorder: (stageIds: string[]) => Promise<WorkflowTemplateDto | null>
}

/**
 * The template's approval chain: stages run one after another in this
 * order. Reordering uses up/down buttons (no drag-and-drop library is
 * installed) and sends the complete new order in one PUT. Read-only once
 * the template is no longer a draft.
 */
export function StageBuilder({ template, editable, busy, onAdd, onUpdate, onDelete, onReorder }: StageBuilderProps) {
  const [adding, setAdding] = useState(false)
  const [pendingDelete, setPendingDelete] = useState<WorkflowStageDto | null>(null)

  const stages = template.stages.slice().sort((a, b) => a.stageOrder - b.stageOrder)
  // What a stage inherits when it leaves an inactivity field empty.
  const templateDefaults: TemplateReminderDefaults = {
    inactivityReminderHours: template.defaultInactivityReminderHours,
    escalateAfterReminderHours: template.defaultEscalateAfterReminderHours,
  }

  function move(index: number, delta: -1 | 1) {
    const ids = stages.map((s) => s.stageId)
    const target = index + delta
    if (target < 0 || target >= ids.length) return
    ;[ids[index], ids[target]] = [ids[target], ids[index]]
    void onReorder(ids)
  }

  return (
    <div className="flex flex-col gap-3">
      {stages.length === 0 && !adding && (
        <div className="rounded-lg border border-dashed border-[var(--border-strong)] bg-card px-6 py-10 text-center text-[13px] text-muted-foreground">
          {editable
            ? "No stages yet. Add the first approval stage - a template needs at least one before it can be activated."
            : "This version has no stages."}
        </div>
      )}

      {stages.map((stage, index) => (
        <StageCard
          key={stage.stageId}
          stage={stage}
          position={index + 1}
          total={stages.length}
          editable={editable}
          busy={busy}
          templateDefaults={templateDefaults}
          onSave={async (payload) => (await onUpdate(stage.stageId, payload)) !== null}
          onDelete={() => setPendingDelete(stage)}
          onMoveUp={() => move(index, -1)}
          onMoveDown={() => move(index, 1)}
        />
      ))}

      {adding && (
        <StageCard
          stage={null}
          position={stages.length + 1}
          total={stages.length + 1}
          editable
          busy={busy}
          templateDefaults={templateDefaults}
          onSave={async (payload) => {
            const ok = (await onAdd(payload)) !== null
            if (ok) setAdding(false)
            return ok
          }}
          onCancelNew={() => setAdding(false)}
        />
      )}

      {editable && !adding && (
        <button
          type="button"
          onClick={() => setAdding(true)}
          disabled={busy}
          className="flex items-center justify-center gap-1.5 rounded-lg border border-dashed border-primary bg-primary/5 px-4 py-3 text-[13px] font-semibold text-primary hover:bg-primary/10 disabled:opacity-60"
        >
          <Plus size={15} />
          Add Stage
        </button>
      )}

      {pendingDelete && (
        <AlertDialog open onOpenChange={(open) => !open && setPendingDelete(null)}>
          <AlertDialogPortal>
            <AlertDialogPopup className="sm:max-w-md">
              <span className="mx-auto mb-4 flex size-11 items-center justify-center rounded-md bg-[var(--rust-tint)] text-[var(--rust)]">
                <Trash2 size={20} aria-hidden="true" />
              </span>
              <AlertDialogTitle>Delete this stage?</AlertDialogTitle>
              <AlertDialogDescription>
                <strong className="font-semibold text-foreground">{pendingDelete.name}</strong> and its participants will be
                removed from this draft. The stages after it move up one place.
              </AlertDialogDescription>
              <div className="flex items-center justify-center gap-2.5">
                <AlertDialogClose className="border border-[var(--border-strong)] bg-card font-semibold text-foreground h-[38px] rounded-btn px-4 text-[13px] inline-flex items-center justify-center">
                  Cancel
                </AlertDialogClose>
                <AlertDialogClose
                  onClick={() => void onDelete(pendingDelete.stageId)}
                  className="border border-[var(--rust)] bg-[var(--rust)] text-white h-[38px] rounded-btn px-4 text-[13px] font-semibold inline-flex items-center justify-center"
                >
                  Delete Stage
                </AlertDialogClose>
              </div>
            </AlertDialogPopup>
          </AlertDialogPortal>
        </AlertDialog>
      )}
    </div>
  )
}
