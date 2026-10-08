"use client"

import { useState } from "react"
import { Forward, UserCog } from "lucide-react"

import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import { fetchDelegateCandidates, type PersonOptionDto } from "@/lib/workflow/api"
import { useWorkflowActions } from "@/lib/workflow/use-workflow-actions"

import { PersonPicker } from "./person-picker"
import { WorkflowFormDialog } from "./workflow-form-dialog"

interface DelegateTaskDialogProps {
  /** delegate: the holder hands it on. reassign: an admin moves it (a reason is required). */
  mode: "delegate" | "reassign"
  taskId: string
  documentTitle: string
  stageLabel: string
  onOpenChange: (open: boolean) => void
}

/**
 * Delegate my task, or (admin) reassign any open task. The candidates come
 * from the backend, which already leaves out the initiator, people who
 * decided in this stage and people without an approve/reject permission.
 */
export function DelegateTaskDialog({ mode, taskId, documentTitle, stageLabel, onOpenChange }: DelegateTaskDialogProps) {
  const actions = useWorkflowActions()
  const [person, setPerson] = useState<PersonOptionDto | null>(null)
  const [text, setText] = useState("")
  const [submitting, setSubmitting] = useState(false)
  const reassign = mode === "reassign"
  const canConfirm = person !== null && (!reassign || text.trim().length > 0)

  async function confirm() {
    if (!person) return
    setSubmitting(true)
    const result = reassign
      ? await actions.reassign(taskId, person.userId, text.trim())
      : await actions.delegate(taskId, person.userId, text.trim() || undefined)
    setSubmitting(false)
    if (result) onOpenChange(false)
  }

  return (
    <WorkflowFormDialog
      icon={reassign ? UserCog : Forward}
      tone="gold"
      title={reassign ? "Reassign this task" : "Delegate this task"}
      description={
        <>
          <strong className="font-semibold text-foreground">{documentTitle}</strong>
          <br />
          <span className="text-[13.5px]">{stageLabel}</span>
          <br />
          <span className="text-[12.5px]">
            {reassign
              ? "The task moves to the person you choose. Its current holder and the new one are both notified."
              : "The task moves to the person you choose; you'll no longer be able to act on it. You're both notified."}
          </span>
        </>
      }
      confirmLabel={reassign ? "Reassign" : "Delegate"}
      canConfirm={canConfirm}
      submitting={submitting}
      onConfirm={() => void confirm()}
      onOpenChange={onOpenChange}
    >
      <div>
        <Label>{reassign ? "Reassign to" : "Delegate to"}</Label>
        <PersonPicker
          queryKey={["delegate", taskId]}
          fetchPeople={(search) => fetchDelegateCandidates(taskId, search)}
          value={person}
          onChange={setPerson}
        />
      </div>
      <div>
        <Label htmlFor="delegate-text">{reassign ? "Reason (required)" : "Note for them (optional)"}</Label>
        <Textarea
          id="delegate-text"
          rows={3}
          maxLength={2000}
          value={text}
          placeholder={reassign ? "e.g. The approver is on leave" : "e.g. I'm away until Friday - please review clause 4"}
          onChange={(e) => setText(e.target.value)}
        />
      </div>
    </WorkflowFormDialog>
  )
}
