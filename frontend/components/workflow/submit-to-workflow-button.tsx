"use client"

import { useEffect, useState } from "react"
import { ChevronDown, Loader2, Send } from "lucide-react"

import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { useWorkflowActions } from "@/lib/workflow/use-workflow-actions"
import { fetchActiveWorkflowTemplates, type WorkflowTemplateOptionDto } from "@/lib/workflow/api"
import { friendlyMessage } from "@/lib/api-error"

import type { WorkflowInstanceDto } from "@/lib/workflow/api"

import { SupportingDocumentPicker, type PickedSupportingDocument } from "./supporting-document-picker"
import { WorkflowFormDialog } from "./workflow-form-dialog"

interface SubmitToWorkflowButtonProps {
  documentId: string
  documentTitle: string
  documentTypeId: string
  /** Hidden while the document already has a workflow in progress. */
  hidden?: boolean
  onSubmitted?: (instance: WorkflowInstanceDto) => void
}

/**
 * "Submit for approval" - starts a workflow template on this document
 * (`POST /workflow/instances`). Rendered only for holders of
 * dlm.submit-documents-for-review. Backend refusals (no active template, no
 * eligible approvers, ...) arrive as friendly toasts.
 */
export function SubmitToWorkflowButton({
  documentId,
  documentTitle,
  documentTypeId,
  hidden,
  onSubmitted,
}: SubmitToWorkflowButtonProps) {
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
          documentTypeId={documentTypeId}
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
  documentTypeId: string
  onOpenChange: (open: boolean) => void
  onSubmitted?: (instance: WorkflowInstanceDto) => void
}

const SELECT_CLASS =
  "h-10 w-full appearance-none rounded-btn border border-border bg-muted px-3 text-[13.5px] text-foreground outline-none focus-visible:border-primary focus-visible:bg-card disabled:cursor-not-allowed disabled:opacity-60"

/**
 * The confirmation on its own, for callers with their own trigger - the row
 * menu in My Documents. Same call and same refusals as the button; whoever
 * renders it checks dlm.submit-documents-for-review first. Supporting documents
 * are optional and attached in the same transaction - an invalid one fails the
 * whole submission, and the dialog stays open so it can be fixed.
 *
 * Fetches the document type's active templates on open: with exactly one,
 * submission works with no extra step (as before); with more than one, a
 * required dropdown picks which workflow to use, so it's traceable later
 * which one actually ran.
 */
export function SubmitToWorkflowDialog({
  documentId,
  documentTitle,
  documentTypeId,
  onOpenChange,
  onSubmitted,
}: SubmitToWorkflowDialogProps) {
  const { submit } = useWorkflowActions()
  const [documents, setDocuments] = useState<PickedSupportingDocument[]>([])
  const [submitting, setSubmitting] = useState(false)
  const [templates, setTemplates] = useState<WorkflowTemplateOptionDto[] | null>(null)
  const [templateError, setTemplateError] = useState<string | null>(null)
  const [selectedTemplateId, setSelectedTemplateId] = useState("")

  useEffect(() => {
    let cancelled = false
    fetchActiveWorkflowTemplates(documentTypeId)
      .then((options) => {
        if (cancelled) return
        setTemplates(options)
        if (options.length === 1) setSelectedTemplateId(options[0].templateId)
      })
      .catch((error) => {
        if (!cancelled) setTemplateError(friendlyMessage(error, "Could not load the approval workflows for this document type."))
      })
    return () => {
      cancelled = true
    }
  }, [documentTypeId])

  const needsChoice = (templates?.length ?? 0) > 1
  const canConfirm = templates !== null && (!needsChoice || selectedTemplateId !== "")

  async function handleConfirm() {
    setSubmitting(true)
    const instance = await submit(
      documentId,
      selectedTemplateId || undefined,
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
          <strong className="font-semibold text-foreground">{documentTitle}</strong> will go through an approval
          workflow, starting with the first stage&apos;s approvers.
        </>
      }
      confirmLabel="Submit"
      canConfirm={canConfirm}
      submitting={submitting}
      onConfirm={() => void handleConfirm()}
      onOpenChange={onOpenChange}
    >
      {templateError && <p className="text-[12.5px] text-destructive">{templateError}</p>}

      {needsChoice && (
        <div className="flex flex-col gap-2">
          <p className="text-[12px] font-bold tracking-[0.08em] text-muted-foreground uppercase">Workflow</p>
          <p className="text-[12px] text-muted-foreground">
            More than one approval workflow applies to this document type - choose which one to use.
          </p>
          <div className="relative">
            <select
              value={selectedTemplateId}
              onChange={(e) => setSelectedTemplateId(e.target.value)}
              className={SELECT_CLASS}
            >
              <option value="">Select a workflow…</option>
              {templates?.map((t) => (
                <option key={t.templateId} value={t.templateId}>
                  {t.name} (v{t.versionNumber})
                </option>
              ))}
            </select>
            <ChevronDown size={14} className="pointer-events-none absolute top-1/2 right-3 -translate-y-1/2 text-[var(--ink-faint)]" />
          </div>
        </div>
      )}

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
