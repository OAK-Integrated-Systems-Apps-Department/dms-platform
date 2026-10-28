"use client"

import { useState } from "react"
import { Forward, MessageCircleQuestion } from "lucide-react"

import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import { lookupPeople, type PersonOptionDto } from "@/lib/workflow/api"
import { useWorkflowActions } from "@/lib/workflow/use-workflow-actions"

import { PersonPicker } from "./person-picker"
import { WorkflowFormDialog } from "./workflow-form-dialog"

type RequestInfoDialogProps =
  | { mode: "raise"; taskId: string; documentTitle: string; onOpenChange: (open: boolean) => void }
  | { mode: "redirect"; infoRequestId: string; documentTitle: string; onOpenChange: (open: boolean) => void }

/**
 * Ask someone for a document or an answer, pausing my task (decision 12) -
 * or, as the submitter or an admin, redirect an open request to someone
 * else. Anyone active in the tenant can be asked, including people who can't
 * open documents (they can still answer with a note).
 */
export function RequestInfoDialog(props: RequestInfoDialogProps) {
  const actions = useWorkflowActions()
  const [person, setPerson] = useState<PersonOptionDto | null>(null)
  const [message, setMessage] = useState("")
  const [dueAt, setDueAt] = useState("")
  const [submitting, setSubmitting] = useState(false)
  const raise = props.mode === "raise"
  const canConfirm = person !== null && (!raise || message.trim().length > 0)

  async function confirm() {
    if (!person) return
    setSubmitting(true)
    const result =
      props.mode === "raise"
        ? await actions.requestInfo(props.taskId, person.userId, message.trim(), dueAt ? new Date(dueAt).toISOString() : null)
        : await actions.redirectInfo(props.infoRequestId, person.userId, message.trim() || undefined)
    setSubmitting(false)
    if (result) props.onOpenChange(false)
  }

  return (
    <WorkflowFormDialog
      icon={raise ? MessageCircleQuestion : Forward}
      tone="gold"
      title={raise ? "Request information" : "Redirect this request"}
      description={
        <>
          <strong className="font-semibold text-foreground">{props.documentTitle}</strong>
          <br />
          <span className="text-[12.5px]">
            {raise
              ? "Your task pauses until every request you send is answered, declined or withdrawn; its due time moves forward by the time it waited. Other approvers carry on."
              : "The request goes to the person you choose instead. The task stays paused."}
          </span>
        </>
      }
      confirmLabel={raise ? "Send Request" : "Redirect"}
      canConfirm={canConfirm}
      submitting={submitting}
      onConfirm={() => void confirm()}
      onOpenChange={props.onOpenChange}
    >
      <div>
        <Label>Ask</Label>
        <PersonPicker
          queryKey={["people", props.mode]}
          fetchPeople={(search) => lookupPeople(search, 20)}
          value={person}
          onChange={setPerson}
          accessHint="Can't open documents - they can still answer with a note."
        />
      </div>
      <div>
        <Label htmlFor="info-message">{raise ? "What do you need? (required)" : "Reason (optional)"}</Label>
        <Textarea
          id="info-message"
          rows={3}
          maxLength={2000}
          value={message}
          placeholder={raise ? "e.g. Please attach the signed annex B" : ""}
          onChange={(e) => setMessage(e.target.value)}
        />
      </div>
      {raise && (
        <div className="sm:w-[260px]">
          <Label htmlFor="info-due">Answer by (optional)</Label>
          <Input id="info-due" type="datetime-local" value={dueAt} onChange={(e) => setDueAt(e.target.value)} />
        </div>
      )}
    </WorkflowFormDialog>
  )
}
