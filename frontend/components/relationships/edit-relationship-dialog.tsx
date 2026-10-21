"use client"

import { useState } from "react"

import { ActionDialog } from "@/components/folders/folder-dialogs"
import { RelationshipTypeSelect } from "@/components/relationships/relationship-type-select"
import { Textarea } from "@/components/ui/textarea"
import { friendlyMessage } from "@/lib/api-error"
import { cycleChainOf } from "@/lib/relationships/api"
import { useUpdateRelationship } from "@/lib/relationships/use-document-relationships"
import { useEnabledRelationshipTypes } from "@/lib/relationships/use-relationship-types"

import type { ChainItemDto, RelatedGroupDto, RelatedItemDto } from "@/lib/relationships/types"

interface EditRelationshipDialogProps {
  item: RelatedItemDto
  group: RelatedGroupDto
  open: boolean
  onOpenChange: (open: boolean) => void
}

/** Change a link's type (to another enabled one with the same direction) and its note. Remount per opening. */
export function EditRelationshipDialog({ item, group, open, onOpenChange }: EditRelationshipDialogProps) {
  const types = useEnabledRelationshipTypes(open)
  const update = useUpdateRelationship()
  const [typeId, setTypeId] = useState(group.relationshipTypeId)
  const [note, setNote] = useState(item.note ?? "")
  const [chain, setChain] = useState<ChainItemDto[]>([])

  const wantSymmetric = item.direction === "symmetric"
  // The current type stays in the list even if it has since been disabled.
  const candidates = (types.data ?? []).filter(
    (t) => (t.directionality === "symmetric") === wantSymmetric || t.relationshipTypeId === group.relationshipTypeId
  )
  const changed = typeId !== group.relationshipTypeId || note.trim() !== (item.note ?? "")

  async function submit(): Promise<string | null> {
    setChain([])
    try {
      await update.mutateAsync({
        id: item.relationshipId,
        body: {
          ...(typeId !== group.relationshipTypeId ? { relationshipTypeId: typeId } : {}),
          note: note.trim(),
        },
      })
      return null
    } catch (error) {
      setChain(cycleChainOf(error))
      return friendlyMessage(error, "Could not change the link.")
    }
  }

  return (
    <ActionDialog
      open={open}
      onOpenChange={onOpenChange}
      title="Edit relationship"
      description={item.other.accessible ? `With ${item.other.title}` : "With a document you can't open"}
      confirmLabel="Save"
      pendingLabel="Saving…"
      onConfirm={submit}
      disabled={!changed}
    >
      <div className="flex flex-col gap-3.5">
        <label className="flex flex-col gap-1.5 text-[12.5px] font-semibold">
          Type
          <RelationshipTypeSelect types={candidates} value={typeId} onChange={setTypeId} />
        </label>
        <label className="flex flex-col gap-1.5 text-[12.5px] font-semibold">
          Note
          <Textarea value={note} maxLength={1000} rows={2} onChange={(e) => setNote(e.target.value)} />
        </label>
        {chain.length > 0 && (
          <ol className="list-decimal rounded-btn border border-border bg-muted px-6 py-2 text-[12.5px]" aria-label="Loop chain">
            {chain.map((c, i) => (
              <li key={`${c.documentId ?? "hidden"}-${i}`}>{c.title}</li>
            ))}
          </ol>
        )}
      </div>
    </ActionDialog>
  )
}
