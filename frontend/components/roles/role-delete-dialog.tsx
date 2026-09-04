"use client"

import { useState } from "react"
import { AlertTriangle, Trash2 } from "lucide-react"

import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogDescription,
  AlertDialogPopup,
  AlertDialogPortal,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"

import type { RoleRecord } from "./types"

interface RoleDeleteDialogProps {
  role: RoleRecord
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Performs the delete and resolves with an error message, or null on success. */
  onConfirm: () => Promise<string | null>
}

/**
 * Delete Role — confirmation dialog for removing a custom role
 * (`DELETE /api/v1/roles/{id}`, a soft-delete server-side, same as
 * deleting a user). Reuses the same `AlertDialog` primitive already used
 * elsewhere in the app; the trigger that opens this (an icon button with
 * a `Tooltip`, matching Edit/Activate-Deactivate) lives in the calling
 * component, not here.
 *
 * The confirm button is a plain `<button>` rather than an
 * `AlertDialogClose`: deleting is async and can fail (e.g. a permission
 * error, or a future backend rule against deleting a role that's still
 * assigned to users), and `AlertDialogClose` always closes on click
 * regardless of outcome. This dialog instead stays open and shows the
 * error inline on failure, and only calls `onOpenChange(false)` itself
 * once the delete actually succeeds.
 */
export function RoleDeleteDialog({
  role,
  open,
  onOpenChange,
  onConfirm,
}: RoleDeleteDialogProps) {
  const [deleting, setDeleting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function handleConfirm() {
    setDeleting(true)
    setError(null)
    const message = await onConfirm()
    setDeleting(false)
    if (message) {
      setError(message)
      return
    }
    onOpenChange(false)
  }

  return (
    <AlertDialog
      open={open}
      onOpenChange={(next) => {
        if (deleting) return
        setError(null)
        onOpenChange(next)
      }}
    >
      <AlertDialogPortal>
        <AlertDialogPopup>
          <span className="mx-auto mb-4 flex size-11 items-center justify-center rounded-[10px] bg-[var(--rust-tint)] text-[var(--rust)]">
            <Trash2 size={20} />
          </span>
          <AlertDialogTitle>Delete this role?</AlertDialogTitle>
          <AlertDialogDescription>
            <strong className="font-semibold text-foreground">{role.name}</strong>{" "}
            will be permanently removed
            {role.assignedUsers > 0 && (
              <>
                , and unassigned from the {role.assignedUsers}{" "}
                {role.assignedUsers === 1
                  ? "user currently holding it"
                  : "users currently holding it"}
              </>
            )}
            . This can't be undone.
          </AlertDialogDescription>

          {error && (
            <div className="mb-4 flex items-start gap-2 rounded-[10px] border border-[var(--rust)] bg-[var(--rust-tint)] px-3.5 py-2.5 text-[12.5px] text-[var(--rust)]">
              <AlertTriangle size={14} className="mt-0.5 shrink-0" />
              <span>{error}</span>
            </div>
          )}

          <div className="flex items-center justify-center gap-2.5">
            <AlertDialogClose
              disabled={deleting}
              className="rounded-[10px] border border-[var(--border-strong)] bg-card px-5 py-2.5 text-[13px] font-semibold text-foreground disabled:opacity-60"
            >
              Cancel
            </AlertDialogClose>
            <button
              type="button"
              onClick={handleConfirm}
              disabled={deleting}
              className="rounded-[10px] border border-[var(--rust)] bg-[var(--rust)] px-5 py-2.5 text-[13px] font-bold tracking-[0.02em] text-white disabled:opacity-60"
            >
              {deleting ? "Deleting…" : "Delete Role"}
            </button>
          </div>
        </AlertDialogPopup>
      </AlertDialogPortal>
    </AlertDialog>
  )
}
