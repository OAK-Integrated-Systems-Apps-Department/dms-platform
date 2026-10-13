"use client"

import { useState } from "react"
import { Loader2, Send } from "lucide-react"

import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogDescription,
  AlertDialogPopup,
  AlertDialogPortal,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { useWorkflowActions } from "@/lib/workflow/use-workflow-actions"

import type { WorkflowInstanceDto } from "@/lib/workflow/api"

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
 * renders it checks dlm.submit-documents-for-review first.
 */
export function SubmitToWorkflowDialog({
  documentId,
  documentTitle,
  onOpenChange,
  onSubmitted,
}: SubmitToWorkflowDialogProps) {
  const { submit } = useWorkflowActions()

  async function handleConfirm() {
    const instance = await submit(documentId)
    if (instance) onSubmitted?.(instance)
  }

  return (
    <AlertDialog open onOpenChange={onOpenChange}>
      <AlertDialogPortal>
        <AlertDialogPopup className="sm:max-w-md">
          <span className="mx-auto mb-4 flex size-11 items-center justify-center rounded-md bg-primary/10 text-primary">
            <Send size={20} aria-hidden="true" />
          </span>
          <AlertDialogTitle>Submit for approval?</AlertDialogTitle>
          <AlertDialogDescription>
            <strong className="font-semibold text-foreground">{documentTitle}</strong> will go through its document
            type&apos;s approval workflow, starting with the first stage&apos;s approvers.
          </AlertDialogDescription>
          <div className="flex items-center justify-center gap-2.5">
            <AlertDialogClose className="rounded-md border border-[var(--border-strong)] bg-card px-5 py-2.5 text-[13px] font-semibold text-foreground">
              Cancel
            </AlertDialogClose>
            <AlertDialogClose
              onClick={() => void handleConfirm()}
              className="rounded-md border border-primary bg-primary px-5 py-2.5 text-[13px] font-bold tracking-[0.02em] text-primary-foreground"
            >
              Submit
            </AlertDialogClose>
          </div>
        </AlertDialogPopup>
      </AlertDialogPortal>
    </AlertDialog>
  )
}
