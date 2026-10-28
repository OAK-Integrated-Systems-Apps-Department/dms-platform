"use client"

import { useState } from "react"
import { Loader2, Send } from "lucide-react"

import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { useWorkflowActions } from "@/lib/workflow/use-workflow-actions"

import type { WorkflowInstanceDto } from "@/lib/workflow/api"

import { SupportingDocumentPicker, type PickedSupportingDocument } from "./supporting-document-picker"
import { WorkflowFormDialog } from "./workflow-form-dialog"

interface SubmitToWorkflowButtonProps {
  documentId: string
  documentTitle: string
  /** Hidden while the document already has a workflow in progress. */
  hidden?: boolean
  onSubmitted?: (instance: WorkflowInstanceDto) => void
}

/**
 * "Submit for approval" - starts the document type's active workflow
 * template on this document (`POST /workflow/instances`). Rendered only for
 * holders of dlm.submit-documents-for-review. Backend refusals (no active
 * template, no eligible approvers, ...) arrive as friendly toasts.
 */
export function SubmitToWorkflowButton({ documentId, documentTitle, hidden, onSubmitted }: SubmitToWorkflowButtonProps) {
  const { hasPermission } = usePermissions()
  const { isActing } = useWorkflowActions()
  const [confirming, setConfirming] = useState(false)

  if (hidden || !hasPermission(PermissionCodes.DLM_SUBMIT_DOCUMENTS_FOR_REVIEW)) return null

  return (
    <>
      <button
        type="button"
        onClick={() => setConfirming(true)}
        disabled={isActing}
        className="flex items-center gap-1.5 rounded-btn border border-primary bg-primary px-4 py-2 text-[13px] font-bold text-primary-foreground disabled:cursor-not-allowed disabled:opacity-60"
      >
        {isActing ? <Loader2 size={15} className="animate-spin" /> : <Send size={15} />}
        Submit for Approval
      </button>

      {confirming && (
        <SubmitToWorkflowDialog
          documentId={documentId}
          documentTitle={documentTitle}
          onOpenChange={(open) => !open && setConfirming(false)}
          onSubmitted={onSubmitted}
        />
      )}
    </>
  )
}

interface SubmitToWorkflowDialogProps {
  documentId: string
  documentTitle: string
  onOpenChange: (open: boolean) => void
  onSubmitted?: (instance: WorkflowInstanceDto) => void
}

/**
 * The confirmation on its own, for callers with their own trigger - the row
 * menu in My Documents. Same call and same refusals as the button; whoever
 * renders it checks dlm.submit-documents-for-review first. Supporting documents
 * are optional and attached in the same transaction - an invalid one fails the
 * whole submission, and the dialog stays open so it can be fixed.
 */
export function SubmitToWorkflowDialog({
  documentId,
  documentTitle,
  onOpenChange,
  onSubmitted,
}: SubmitToWorkflowDialogProps) {
  const { submit } = useWorkflowActions()
  const [documents, setDocuments] = useState<PickedSupportingDocument[]>([])
  const [submitting, setSubmitting] = useState(false)

  async function handleConfirm() {
    setSubmitting(true)
    const instance = await submit(
      documentId,
      documents.map(({ documentId, documentVersionId, source, note }) => ({ documentId, documentVersionId, source, note }))
    )
    setSubmitting(false)
    if (instance) {
      onSubmitted?.(instance)
      onOpenChange(false)
    }
  }

  return (
    <WorkflowFormDialog
      icon={Send}
      title="Submit for approval?"
      description={
        <>
          <strong className="font-semibold text-foreground">{documentTitle}</strong> will go through its document
          type&apos;s approval workflow, starting with the first stage&apos;s approvers.
        </>
      }
      confirmLabel="Submit"
      canConfirm
      submitting={submitting}
      onConfirm={() => void handleConfirm()}
      onOpenChange={onOpenChange}
    >
      <div className="flex flex-col gap-2">
        <p className="text-[12px] font-bold tracking-[0.08em] text-muted-foreground uppercase">Supporting documents (optional)</p>
        <p className="text-[12px] text-muted-foreground">
          Attach evidence for the approvers. It isn&apos;t approved itself, and later versions of it don&apos;t affect this
          workflow.
        </p>
        <SupportingDocumentPicker value={documents} onChange={setDocuments} excludeDocumentId={documentId} />
      </div>
    </WorkflowFormDialog>
  )
}
