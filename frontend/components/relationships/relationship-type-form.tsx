"use client"

import { useState } from "react"

import { ActionDialog } from "@/components/folders/folder-dialogs"
import { Input } from "@/components/ui/input"
import { Switch } from "@/components/ui/switch"
import { friendlyMessage } from "@/lib/api-error"

import type {
  CreateRelationshipTypeRequestDto,
  Directionality,
  RelationshipTypeDto,
  UpdateRelationshipTypeRequestDto,
} from "@/lib/relationships/types"

interface RelationshipTypeFormProps {
  /** Null = create. */
  type: RelationshipTypeDto | null
  open: boolean
  onOpenChange: (open: boolean) => void
  onCreate: (body: CreateRelationshipTypeRequestDto) => Promise<unknown>
  onUpdate: (id: string, body: UpdateRelationshipTypeRequestDto) => Promise<unknown>
}

/**
 * Create or edit a relationship type. A built-in type shows only its labels
 * (its direction and loop rule are fixed); a custom type's direction is
 * frozen once it has links - the server says so, its message is shown as is.
 * Remount (new `key`) per opening.
 */
export function RelationshipTypeForm({ type, open, onOpenChange, onCreate, onUpdate }: RelationshipTypeFormProps) {
  const [label, setLabel] = useState(type?.label ?? "")
  const [inverseLabel, setInverseLabel] = useState(type?.inverseLabel ?? "")
  const [directionality, setDirectionality] = useState<Directionality>(type?.directionality ?? "directional")
  const [preventsCycles, setPreventsCycles] = useState(type?.preventsCycles ?? false)

  const symmetric = directionality === "symmetric"
  const fixedShape = type?.system ?? false

  async function submit(): Promise<string | null> {
    try {
      if (type) {
        await onUpdate(type.relationshipTypeId, {
          label: label.trim(),
          ...(symmetric ? {} : { inverseLabel: inverseLabel.trim() }),
          ...(fixedShape ? {} : { directionality, preventsCycles: symmetric ? false : preventsCycles }),
        })
      } else {
        await onCreate({
          label: label.trim(),
          inverseLabel: symmetric ? undefined : inverseLabel.trim() || undefined,
          directionality,
          preventsCycles: symmetric ? false : preventsCycles,
        })
      }
      return null
    } catch (error) {
      return friendlyMessage(error, "Could not save the relationship type.")
    }
  }

  return (
    <ActionDialog
      open={open}
      onOpenChange={onOpenChange}
      title={type ? "Edit relationship type" : "New relationship type"}
      description="The label is how a link reads from the first document; the inverse label is how it reads from the other."
      confirmLabel={type ? "Save" : "Create"}
      pendingLabel="Saving…"
      onConfirm={submit}
      disabled={label.trim().length === 0}
    >
      <div className="flex flex-col gap-3.5">
        <label className="flex flex-col gap-1.5 text-[12.5px] font-semibold">
          Label
          <Input value={label} maxLength={120} onChange={(e) => setLabel(e.target.value)} placeholder="e.g. Supersedes" />
        </label>
        {!symmetric && (
          <label className="flex flex-col gap-1.5 text-[12.5px] font-semibold">
            Inverse label
            <Input
              value={inverseLabel}
              maxLength={120}
              onChange={(e) => setInverseLabel(e.target.value)}
              placeholder="e.g. Superseded by"
            />
          </label>
        )}
        {!fixedShape && (
          <>
            <label className="flex flex-col gap-1.5 text-[12.5px] font-semibold">
              Direction
              <select
                value={directionality}
                onChange={(e) => setDirectionality(e.target.value as Directionality)}
                className="h-10 rounded-btn border border-input bg-transparent px-2.5 text-[13px] font-normal"
              >
                <option value="directional">One-way (has an inverse label)</option>
                <option value="symmetric">Two-way (reads the same from both documents)</option>
              </select>
            </label>
            {!symmetric && (
              <label className="flex items-center gap-2.5 text-[12.5px] font-semibold">
                <Switch size="sm" checked={preventsCycles} onCheckedChange={setPreventsCycles} />
                Prevent loops (A → B → … → A)
              </label>
            )}
          </>
        )}
        {fixedShape && (
          <p className="text-[12px] text-muted-foreground">
            This is a built-in type: its direction and loop rule are fixed.
          </p>
        )}
      </div>
    </ActionDialog>
  )
}
