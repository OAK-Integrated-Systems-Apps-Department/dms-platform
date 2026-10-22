"use client"

import { useState } from "react"

import { ActionDialog } from "@/components/folders/folder-dialogs"
import { RelationshipTypeSelect } from "@/components/relationships/relationship-type-select"
import { DocumentPicker } from "@/components/workflow/document-picker"
import { friendlyMessage } from "@/lib/api-error"
import { bulkErrorsOf, cycleChainOf } from "@/lib/relationships/api"
import {
  useCreateRelationship,
  useCreateRelationshipsBulk,
} from "@/lib/relationships/use-document-relationships"
import { useEnabledRelationshipTypes } from "@/lib/relationships/use-relationship-types"

import type { DocumentListItemDto } from "@/lib/documents/api"
import type { BulkErrorDto, ChainItemDto } from "@/lib/relationships/types"

interface LinkToDialogProps {
  /** The documents chosen in the list - each becomes the source of a link to the picked one. */
  sources: Pick<DocumentListItemDto, "documentId" | "title">[]
  open: boolean
  onOpenChange: (open: boolean) => void
  onLinked?: () => void
}

/** "Link to…" from the document list: one type, one target document, the chosen documents as sources. Remount per opening. */
export function LinkToDialog({ sources, open, onOpenChange, onLinked }: LinkToDialogProps) {
  const types = useEnabledRelationshipTypes(open)
  const create = useCreateRelationship()
  const createBulk = useCreateRelationshipsBulk()
  const [typeId, setTypeId] = useState("")
  const [target, setTarget] = useState<DocumentListItemDto | null>(null)
  const [chain, setChain] = useState<ChainItemDto[]>([])
  const [bulkErrors, setBulkErrors] = useState<BulkErrorDto[]>([])

  const type = (types.data ?? []).find((t) => t.relationshipTypeId === typeId)
  const titleOf = (id: string) => sources.find((s) => s.documentId === id)?.title ?? "A document"

  async function submit(): Promise<string | null> {
    if (!type || !target) return "Choose a type and a document to link to."
    setChain([])
    setBulkErrors([])
    try {
      if (sources.length === 1) {
        await create.mutateAsync({
          relationshipTypeId: type.relationshipTypeId,
          sourceDocumentId: sources[0].documentId,
          targetDocumentId: target.documentId,
        })
      } else {
        await createBulk.mutateAsync({
          relationshipTypeId: type.relationshipTypeId,
          sourceDocumentIds: sources.map((s) => s.documentId),
          targetDocumentIds: [target.documentId],
        })
      }
      onLinked?.()
      return null
    } catch (error) {
      setChain(cycleChainOf(error))
      setBulkErrors(bulkErrorsOf(error))
      return friendlyMessage(error, "Could not link the documents.")
    }
  }

  const heading = sources.length === 1 ? `“${sources[0].title}”` : `${sources.length} documents`

  return (
    <ActionDialog
      open={open}
      onOpenChange={onOpenChange}
      title={`Link ${heading}`}
      description={
        type && type.directionality === "directional"
          ? `${sources.length === 1 ? "It" : "Each"} ${type.label.toLowerCase()} the document you choose.`
          : "Choose how they are related and the document to link to."
      }
      confirmLabel="Link"
      pendingLabel="Linking…"
      onConfirm={submit}
      disabled={!type || !target}
    >
      <div className="flex flex-col gap-3.5">
        <label className="flex flex-col gap-1.5 text-[12.5px] font-semibold">
          Type
          <RelationshipTypeSelect types={types.data ?? []} value={typeId} onChange={setTypeId} />
        </label>
        {target ? (
          <p className="flex items-center justify-between rounded-btn border border-border bg-muted px-3 py-2 text-[12.5px]">
            <span>
              Link to <strong>{target.title}</strong>
            </span>
            <button type="button" className="text-primary hover:underline" onClick={() => setTarget(null)}>
              Change
            </button>
          </p>
        ) : (
          <DocumentPicker pickedIds={sources.map((s) => s.documentId)} onPick={setTarget} />
        )}
        {chain.length > 0 && (
          <ol className="list-decimal rounded-btn border border-border bg-muted px-6 py-2 text-[12.5px]" aria-label="Loop chain">
            {chain.map((c, i) => (
              <li key={`${c.documentId ?? "hidden"}-${i}`}>{c.title}</li>
            ))}
          </ol>
        )}
        {bulkErrors.length > 0 && (
          <ul className="list-disc rounded-btn border border-border bg-muted px-6 py-2 text-[12.5px]" aria-label="Documents that can't be linked">
            {bulkErrors.map((e, i) => (
              <li key={`${e.documentId}-${i}`}>
                <strong>{titleOf(e.documentId)}</strong>: {e.message}
              </li>
            ))}
          </ul>
        )}
      </div>
    </ActionDialog>
  )
}
