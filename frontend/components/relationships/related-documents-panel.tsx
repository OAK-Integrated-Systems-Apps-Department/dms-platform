"use client"

import { useState } from "react"
import Link from "next/link"
import { Lock, Pencil, Plus, Search, Trash2 } from "lucide-react"
import { toast } from "sonner"

import { AddRelationshipDialog } from "@/components/relationships/add-relationship-dialog"
import { EditRelationshipDialog } from "@/components/relationships/edit-relationship-dialog"
import { ConfirmActionDialog } from "@/components/ui/confirm-action-dialog"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Panel } from "@/components/ui/panel"
import { friendlyMessage } from "@/lib/api-error"
import {
  useDocumentRelationships,
  useRemoveRelationship,
} from "@/lib/relationships/use-document-relationships"
import { formatDateTime } from "@/lib/utils"

import type { RelatedGroupDto, RelatedItemDto } from "@/lib/relationships/types"

interface RelatedDocumentsPanelProps {
  documentId: string
  /** No add, edit or remove - e.g. an approver reading a document under review. */
  readOnly?: boolean
}

/**
 * The Related Documents panel: every link of the document, grouped by type and read
 * from this document's side ("Supersedes" / "Superseded by"). A related document the
 * viewer can't open shows only that a link exists - no title, no link, no details.
 * One in the Recycle Bin is named but not linked. What the viewer may change comes
 * from the server per row; the server checks it again.
 */
export function RelatedDocumentsPanel({ documentId, readOnly = false }: RelatedDocumentsPanelProps) {
  const { data, isPending, isError, error } = useDocumentRelationships(documentId)
  const remove = useRemoveRelationship()
  const [adding, setAdding] = useState(false)
  const [editing, setEditing] = useState<{ item: RelatedItemDto; group: RelatedGroupDto } | null>(null)
  const [removing, setRemoving] = useState<RelatedItemDto | null>(null)

  const linkedIds = (data?.groups ?? []).flatMap((g) =>
    g.items.flatMap((i) => (i.other.documentId ? [i.other.documentId] : []))
  )
  const supersedes = data?.groups.some((g) => g.typeCode === "SUPERSEDES") ?? false

  // Read-only on another page (a workflow run): someone who may not open the document simply sees no panel.
  if (readOnly && isError) return null

  return (
    <div id="relationships" className="scroll-mt-24">
      <Panel title="Related Documents">
        <div className="mt-2 flex flex-col gap-3">
          {isPending && <p className="text-[12.5px] text-muted-foreground">Loading related documents…</p>}
          {isError && (
            <p role="alert" className="text-[12.5px] text-[var(--rust)]">
              {friendlyMessage(error, "Could not load the related documents.")}
            </p>
          )}
          {data && data.groups.length === 0 && (
            <p className="text-[12.5px] text-muted-foreground">No documents are linked to this one yet.</p>
          )}
          {data?.groups.map((group) => (
            <section key={group.relationshipTypeId} aria-label={group.typeLabel}>
              <h3 className="mb-1 flex items-center gap-2 text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
                {group.typeLabel}
                {group.typeInverseLabel !== group.typeLabel && <span> / {group.typeInverseLabel}</span>}
                {!group.typeEnabled && <Badge variant="muted">Disabled type</Badge>}
              </h3>
              <ul className="flex flex-col gap-2">
                {group.items.map((item) => (
                  <RelatedRow
                    key={item.relationshipId}
                    item={item}
                    readOnly={readOnly}
                    onEdit={() => setEditing({ item, group })}
                    onRemove={() => setRemoving(item)}
                  />
                ))}
              </ul>
            </section>
          ))}
          <div className="flex flex-wrap items-center gap-3">
            {data?.canAdd && !readOnly && (
              <Button variant="outline" size="sm" onClick={() => setAdding(true)}>
                <Plus />
                Add relationship
              </Button>
            )}
            {supersedes && (
              <Link
                href={`/documents?relatedTo=${encodeURIComponent(documentId)}&relationshipType=SUPERSEDES&direction=source`}
                className="flex items-center gap-1 text-[12px] text-primary hover:underline"
              >
                <Search size={12} />
                Find documents that supersede this
              </Link>
            )}
          </div>
        </div>
      </Panel>

      {adding && (
        <AddRelationshipDialog documentId={documentId} linkedIds={linkedIds} open={adding} onOpenChange={setAdding} />
      )}
      {editing && (
        <EditRelationshipDialog
          key={editing.item.relationshipId}
          item={editing.item}
          group={editing.group}
          open
          onOpenChange={(next) => !next && setEditing(null)}
        />
      )}
      <ConfirmActionDialog
        open={removing !== null}
        onOpenChange={(next) => !next && setRemoving(null)}
        title="Remove this relationship?"
        description="The link disappears from both documents. The audit trail keeps a record of it."
        confirmLabel="Remove"
        pendingLabel="Removing…"
        onConfirm={async () => {
          if (!removing) return null
          try {
            await remove.mutateAsync(removing.relationshipId)
            toast.success("Relationship removed.")
            return null
          } catch (failure) {
            return friendlyMessage(failure, "Could not remove the link.")
          }
        }}
      />
    </div>
  )
}

function RelatedRow({
  item,
  readOnly,
  onEdit,
  onRemove,
}: {
  item: RelatedItemDto
  readOnly: boolean
  onEdit: () => void
  onRemove: () => void
}) {
  const { other } = item
  return (
    <li className="flex items-start justify-between gap-2 text-[12.5px]">
      <span className="min-w-0">
        <span className="flex flex-wrap items-center gap-x-2 gap-y-0.5">
          <span className="text-[11.5px] text-muted-foreground">{item.label}</span>
          {!other.accessible ? (
            <span className="flex items-center gap-1.5 text-foreground" title="You don't have access to this document">
              <Lock size={12} className="shrink-0 text-muted-foreground" />
              <span className="font-semibold">A document you can&apos;t open</span>
            </span>
          ) : other.inRecycleBin ? (
            <>
              <span className="font-semibold text-foreground">{other.title}</span>
              <Badge variant="rust">In Recycle Bin</Badge>
            </>
          ) : (
            <Link href={`/documents/${other.documentId}`} className="font-semibold text-primary hover:underline">
              {other.title}
            </Link>
          )}
          {other.accessible && other.documentTypeName && (
            <span className="text-[11.5px] text-muted-foreground">{other.documentTypeName}</span>
          )}
          {other.accessible && other.status && <Badge variant="muted">{other.status}</Badge>}
        </span>
        <span className="block text-[11.5px] text-muted-foreground">
          {item.createdBy ? `Linked by ${item.createdBy.name}` : "Linked automatically"} · {formatDateTime(item.createdAt)}
        </span>
        {item.note && <span className="block text-foreground">{item.note}</span>}
      </span>
      {!readOnly && (item.canEdit || item.canRemove) && (
        <span className="flex shrink-0 items-center gap-0.5">
          {item.canEdit && (
            <button
              type="button"
              onClick={onEdit}
              className="flex size-6 items-center justify-center rounded-sm text-muted-foreground hover:text-foreground"
              aria-label="Edit relationship"
              title="Edit relationship"
            >
              <Pencil size={13} />
            </button>
          )}
          {item.canRemove && (
            <button
              type="button"
              onClick={onRemove}
              className="flex size-6 items-center justify-center rounded-sm text-muted-foreground hover:text-[var(--rust)]"
              aria-label="Remove relationship"
              title="Remove relationship"
            >
              <Trash2 size={13} />
            </button>
          )}
        </span>
      )}
    </li>
  )
}
