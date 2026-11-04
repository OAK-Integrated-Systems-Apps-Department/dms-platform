"use client"

import { LoadFailed } from "@/components/ui/load-failed"
import { useState } from "react"
import { X } from "lucide-react"

import { ActionDialog } from "@/components/folders/folder-dialogs"
import { RelationshipTypeSelect } from "@/components/relationships/relationship-type-select"
import { DocumentPicker } from "@/components/workflow/document-picker"
import { Textarea } from "@/components/ui/textarea"
import { friendlyMessage } from "@/lib/api-error"
import { bulkErrorsOf, cycleChainOf } from "@/lib/relationships/api"
import {
  useCreateRelationship,
  useCreateRelationshipsBulk,
} from "@/lib/relationships/use-document-relationships"
import { useEnabledRelationshipTypes } from "@/lib/relationships/use-relationship-types"

import type { DocumentListItemDto } from "@/lib/documents/api"
import type { BulkErrorDto, ChainItemDto } from "@/lib/relationships/types"

interface AddRelationshipDialogProps {
  /** The document the links are made from. */
  documentId: string
  /** Documents already linked to it - hidden from the picker. */
  linkedIds: string[]
  open: boolean
  onOpenChange: (open: boolean) => void
}

/**
 * Link this document to one or several others with one type. A one-way type
 * asks which way it reads. Several documents go through the bulk endpoint -
 * all or nothing - and a refused request says why, per document; a loop is
 * shown with the chain the server named. Remount (new `key`) per opening.
 */
export function AddRelationshipDialog({ documentId, linkedIds, open, onOpenChange }: AddRelationshipDialogProps) {
  const types = useEnabledRelationshipTypes(open)
  const create = useCreateRelationship()
  const createBulk = useCreateRelationshipsBulk()
  const [typeId, setTypeId] = useState("")
  const [direction, setDirection] = useState<"outgoing" | "incoming">("outgoing")
  const [picked, setPicked] = useState<DocumentListItemDto[]>([])
  const [note, setNote] = useState("")
  const [chain, setChain] = useState<ChainItemDto[]>([])
  const [bulkErrors, setBulkErrors] = useState<BulkErrorDto[]>([])

  const type = (types.data ?? []).find((t) => t.relationshipTypeId === typeId)
  const directional = type?.directionality === "directional"
  const titleOf = (id: string) => picked.find((d) => d.documentId === id)?.title ?? "A document"

  async function submit(): Promise<string | null> {
    if (!type || picked.length === 0) return "Choose a type and at least one document."
    setChain([])
    setBulkErrors([])
    const incoming = directional && direction === "incoming"
    const trimmed = note.trim() || undefined
    try {
      if (picked.length === 1) {
        const other = picked[0].documentId
        await create.mutateAsync({
          relationshipTypeId: type.relationshipTypeId,
          sourceDocumentId: incoming ? other : documentId,
          targetDocumentId: incoming ? documentId : other,
          note: trimmed,
        })
      } else {
        const ids = picked.map((d) => d.documentId)
        await createBulk.mutateAsync({
          relationshipTypeId: type.relationshipTypeId,
          sourceDocumentIds: incoming ? ids : [documentId],
          targetDocumentIds: incoming ? [documentId] : ids,
          note: trimmed,
        })
      }
      return null
    } catch (error) {
      setChain(cycleChainOf(error))
      setBulkErrors(bulkErrorsOf(error))
      return friendlyMessage(error, "Could not link the documents.")
    }
  }

  return (
    <ActionDialog
      open={open}
      onOpenChange={onOpenChange}
      title="Add relationship"
      description="Link this document to another so they can be found from each other."
      confirmLabel={picked.length > 1 ? `Link ${picked.length} documents` : "Link"}
      pendingLabel="Linking…"
      onConfirm={submit}
      disabled={!type || picked.length === 0}
    >
      <div className="flex flex-col gap-3.5">
        <label className="flex flex-col gap-1.5 text-[12.5px] font-semibold">
          Type
          <RelationshipTypeSelect
            types={types.data ?? []}
            value={typeId}
            onChange={(id) => {
              setTypeId(id)
              setDirection("outgoing")
            }}
          />
        </label>
        {types.isError && (
          <LoadFailed error={types.error} fallback="Could not load the types." quiet />
        )}

        {type && directional && (
          <fieldset className="flex flex-col gap-1 text-[12.5px]">
            <legend className="mb-1 font-semibold">Direction</legend>
            <label className="flex items-center gap-2">
              <input type="radio" name="direction" checked={direction === "outgoing"} onChange={() => setDirection("outgoing")} />
              This document <strong>{type.label.toLowerCase()}</strong> the chosen one
            </label>
            <label className="flex items-center gap-2">
              <input type="radio" name="direction" checked={direction === "incoming"} onChange={() => setDirection("incoming")} />
              This document is <strong>{type.inverseLabel.toLowerCase()}</strong> the chosen one
            </label>
          </fieldset>
        )}
        {type && !directional && (
          <p className="text-[12px] text-muted-foreground">
            &quot;{type.label}&quot; reads the same from both documents.
          </p>
        )}

        {picked.length > 0 && (
          <ul className="flex flex-wrap gap-1.5" aria-label="Chosen documents">
            {picked.map((d) => (
              <li
                key={d.documentId}
                className="flex items-center gap-1 rounded-btn border border-border bg-muted px-2 py-1 text-[12px]"
              >
                {d.title}
                <button
                  type="button"
                  aria-label={`Remove ${d.title}`}
                  onClick={() => setPicked(picked.filter((p) => p.documentId !== d.documentId))}
                >
                  <X size={12} />
                </button>
              </li>
            ))}
          </ul>
        )}
        <DocumentPicker
          excludeDocumentId={documentId}
          pickedIds={[...picked.map((d) => d.documentId), ...linkedIds]}
          onPick={(d) => setPicked([...picked, d])}
        />

        <label className="flex flex-col gap-1.5 text-[12.5px] font-semibold">
          Note (optional)
          <Textarea value={note} maxLength={1000} rows={2} onChange={(e) => setNote(e.target.value)} />
        </label>

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
