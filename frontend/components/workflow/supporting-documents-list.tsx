"use client"

import { useState } from "react"
import Link from "next/link"
import { FileText, Lock, Paperclip, Trash2 } from "lucide-react"

import { useWorkflowActions } from "@/lib/workflow/use-workflow-actions"
import { formatDateTime } from "@/lib/utils"

import { fetchSupportingDocumentContent, type SupportingDocumentDto } from "@/lib/workflow/api"

import { SupportingDocumentPicker, type PickedSupportingDocument } from "./supporting-document-picker"
import { useDocumentPreview } from "./use-document-preview"
import { WorkflowFormDialog } from "./workflow-form-dialog"

/**
 * One supporting document. A viewer who can open documents can preview the
 * pinned version in the shared viewer and still "View details"; one who can't
 * sees the title and note with a lock (decision 18) - there is no per-document
 * grant to bypass.
 */
export function SupportingDocumentRow({ document, onRemove }: { document: SupportingDocumentDto; onRemove?: () => void }) {
  const preview = useDocumentPreview()
  return (
    <li className="flex items-start justify-between gap-2 text-[12.5px]">
      <span className="min-w-0">
        <span className="flex flex-wrap items-center gap-x-2 gap-y-0.5">
          {document.canOpen ? (
            <>
              <span className="flex items-center gap-1.5">
                <FileText size={13} className="shrink-0 text-primary" />
                <button
                  type="button"
                  onClick={() =>
                    preview.open(
                      {
                        documentId: document.documentId,
                        title: document.title ?? "Document",
                        fileName: document.fileName,
                        mimeType: document.mimeType,
                        sizeBytes: document.sizeBytes,
                        documentTypeName: null,
                        createdAt: document.addedAt,
                      },
                      {
                        // The pinned version that was attached (decision 17), not the document's current one.
                        contentSource: () =>
                          fetchSupportingDocumentContent(document.instanceId, document.supportingDocumentId),
                        contentKey: document.supportingDocumentId,
                      }
                    )
                  }
                  className="text-left font-semibold text-primary hover:underline"
                >
                  {document.title ?? "Document"}
                </button>
              </span>
              <Link href={`/documents/${document.documentId}`} className="text-[11.5px] text-muted-foreground hover:text-foreground hover:underline">
                View details
              </Link>
            </>
          ) : (
            <span className="flex items-center gap-1.5 text-foreground" title="You don't have permission to open documents">
              <Lock size={12} className="shrink-0 text-muted-foreground" />
              <span className="font-semibold">{document.title ?? "Document"}</span>
            </span>
          )}
          {document.versionNumber != null && <span className="text-[11.5px] text-muted-foreground">v{document.versionNumber}</span>}
        </span>
        <span className="block text-[11.5px] text-muted-foreground">
          {document.source === "uploaded_new" ? "Uploaded" : "Linked"} by {document.addedBy.displayName ?? "someone"} ·{" "}
          {formatDateTime(document.addedAt)}
        </span>
        {document.note && <span className="block text-foreground">{document.note}</span>}
      </span>
      {onRemove && (
        <button
          type="button"
          onClick={onRemove}
          className="flex size-6 shrink-0 items-center justify-center rounded-sm text-muted-foreground hover:text-[var(--rust)]"
          aria-label={`Remove ${document.title ?? "document"}`}
        >
          <Trash2 size={13} />
        </button>
      )}
      {preview.element}
    </li>
  )
}

interface SupportingDocumentsListProps {
  instanceId: string
  /** The document under approval - left out of the picker. */
  documentId: string
  documents: SupportingDocumentDto[]
  currentUserId: string | undefined
  /** Participants of a running workflow who can open documents may attach more. */
  canAttach: boolean
  inProgress: boolean
}

/** Evidence attached to the run (decision 17): never routed for approval, pinned to a version. */
export function SupportingDocumentsList({ instanceId, documentId, documents, currentUserId, canAttach, inProgress }: SupportingDocumentsListProps) {
  const actions = useWorkflowActions()
  const [attaching, setAttaching] = useState(false)
  const standalone = documents.filter((d) => d.infoRequestId === null)

  if (standalone.length === 0 && !(canAttach && inProgress)) return null

  return (
    <div>
      <div className="mb-2 flex items-center justify-between">
        <p className="text-[12px] font-bold tracking-[0.08em] text-muted-foreground uppercase">Supporting documents</p>
        {canAttach && inProgress && (
          <button
            type="button"
            onClick={() => setAttaching(true)}
            className="flex items-center gap-1 rounded-sm border border-[var(--border-strong)] bg-card px-2.5 py-1 text-[12px] font-semibold text-muted-foreground hover:text-foreground"
          >
            <Paperclip size={12} /> Attach
          </button>
        )}
      </div>
      {standalone.length === 0 ? (
        <p className="text-[12.5px] text-muted-foreground">Nothing attached yet.</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {standalone.map((d) => (
            <SupportingDocumentRow
              key={d.supportingDocumentId}
              document={d}
              onRemove={inProgress && d.addedBy.userId === currentUserId
                ? () => void actions.removeDocument(instanceId, d.supportingDocumentId)
                : undefined}
            />
          ))}
        </ul>
      )}
      {attaching && (
        <AttachDialog instanceId={instanceId} documentId={documentId} onOpenChange={(o) => !o && setAttaching(false)} />
      )}
    </div>
  )
}

function AttachDialog({
  instanceId,
  documentId,
  onOpenChange,
}: {
  instanceId: string
  documentId: string
  onOpenChange: (open: boolean) => void
}) {
  const actions = useWorkflowActions()
  const [documents, setDocuments] = useState<PickedSupportingDocument[]>([])
  const [submitting, setSubmitting] = useState(false)

  async function confirm() {
    setSubmitting(true)
    let ok = true
    for (const { documentId, documentVersionId, source, note } of documents) {
      ok = (await actions.attachDocument(instanceId, { documentId, documentVersionId, source, note })) !== null && ok
    }
    setSubmitting(false)
    if (ok) onOpenChange(false)
  }

  return (
    <WorkflowFormDialog
      icon={Paperclip}
      title="Attach supporting documents"
      description="Evidence for the approvers. It isn't approved itself, and later versions of it don't affect this workflow."
      confirmLabel="Attach"
      canConfirm={documents.length > 0}
      submitting={submitting}
      onConfirm={() => void confirm()}
      onOpenChange={onOpenChange}
    >
      <SupportingDocumentPicker value={documents} onChange={setDocuments} excludeDocumentId={documentId} />
    </WorkflowFormDialog>
  )
}
