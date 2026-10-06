"use client"

import { useState } from "react"
import { MessageSquareReply, MessageSquareX } from "lucide-react"

import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import { useWorkflowActions } from "@/lib/workflow/use-workflow-actions"

import type { InfoRequestDto } from "@/lib/workflow/api"

import { SupportingDocumentPicker, type PickedSupportingDocument } from "./supporting-document-picker"
import { WorkflowFormDialog } from "./workflow-form-dialog"

interface RespondInfoDialogProps {
  request: InfoRequestDto
  /** respond: a note and/or documents. decline: a reason is required. */
  mode: "respond" | "decline"
  onOpenChange: (open: boolean) => void
}

/** Answer (or decline) an information request addressed to me (decision 15). */
export function RespondInfoDialog({ request, mode, onOpenChange }: RespondInfoDialogProps) {
  const actions = useWorkflowActions()
  const [text, setText] = useState("")
  const [documents, setDocuments] = useState<PickedSupportingDocument[]>([])
  const [submitting, setSubmitting] = useState(false)
  const decline = mode === "decline"
  const canConfirm = decline ? text.trim().length > 0 : text.trim().length > 0 || documents.length > 0

  async function confirm() {
    setSubmitting(true)
    const result = decline
      ? await actions.declineInfo(request.infoRequestId, text.trim())
      : await actions.respondInfo(
          request.infoRequestId,
          text.trim() || null,
          documents.map(({ documentId, documentVersionId, source, note }) => ({ documentId, documentVersionId, source, note }))
        )
    setSubmitting(false)
    if (result) onOpenChange(false)
  }

  return (
    <WorkflowFormDialog
      icon={decline ? MessageSquareX : MessageSquareReply}
      tone={decline ? "rust" : "primary"}
      title={decline ? "Decline this request" : "Answer this request"}
      description={
        <>
          <strong className="font-semibold text-foreground">{request.documentTitle ?? "A document"}</strong>
          <br />
          <span className="text-[13px]">
            {request.requestedBy.displayName ?? "An approver"} asked: <em className="whitespace-pre-wrap">{request.message}</em>
          </span>
        </>
      }
      confirmLabel={decline ? "Decline" : "Send Answer"}
      canConfirm={canConfirm}
      submitting={submitting}
      onConfirm={() => void confirm()}
      onOpenChange={onOpenChange}
    >
      <div>
        <Label htmlFor="respond-text">{decline ? "Reason (required)" : "Note"}</Label>
        <Textarea
          id="respond-text"
          rows={3}
          maxLength={2000}
          value={text}
          placeholder={decline ? "e.g. I don't have this - ask Finance" : "Anything they should know"}
          onChange={(e) => setText(e.target.value)}
        />
      </div>
      {!decline && (
        <div>
          <Label>Supporting documents</Label>
          <SupportingDocumentPicker value={documents} onChange={setDocuments} />
        </div>
      )}
    </WorkflowFormDialog>
  )
}
