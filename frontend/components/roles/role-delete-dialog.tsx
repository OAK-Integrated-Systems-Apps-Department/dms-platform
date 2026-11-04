"use client"

import { useState } from "react"
import { Trash2 } from "lucide-react"

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
 * error, or a backend rule against deleting a role that's still assigned
 * to users), and `AlertDialogClose` always closes on click regardless of
 * outcome. This dialog instead stays open on failure — the caller already
 * surfaces the error via a toast, so it isn't repeated here — and only
 * calls `onOpenChange(false)` itself once the delete actually succeeds.
 */
export function RoleDeleteDialog({
  role,
  open,
  onOpenChange,
  onConfirm,
}: RoleDeleteDialogProps) {
  const [deleting, setDeleting] = useState(false)

  async function handleConfirm() {
    setDeleting(true)
    const message = await onConfirm()
    setDeleting(false)
    if (message) {
      return
    }
    onOpenChange(false)
  }

  return (
    <AlertDialog
      open={open}
      onOpenChange={(next) => {
        if (deleting) return
        onOpenChange(next)
      }}
    >
      <AlertDialogPortal>
        <AlertDialogPopup className="max-w-[460px]">
          <span className="mx-auto mb-4 flex size-11 items-center justify-center rounded-md bg-[var(--rust-tint)] text-[var(--rust)]">
            <Trash2 size={20} />
          </span>
          <AlertDialogTitle>Delete this role?</AlertDialogTitle>
          <AlertDialogDescription>
            <strong className="font-semibold text-foreground">
              {role.name}
            </strong>{" "}
            will be permanently removed
            {role.assignedUsers > 0 && (
              <>
                , and unassigned from the {role.assignedUsers}{" "}
                {role.assignedUsers === 1
                  ? "user currently holding it"
                  : "users currently holding it"}
              </>
            )}
            . This can&apos;t be undone.
          </AlertDialogDescription>

          <div className="flex items-center justify-center gap-2.5">
            <AlertDialogClose
              disabled={deleting}
              className="border border-[var(--border-strong)] bg-card font-semibold text-foreground disabled:opacity-60 h-[38px] rounded-btn px-4 text-[13px] inline-flex items-center justify-center"
            >
              Cancel
            </AlertDialogClose>
            <button
              type="button"
              onClick={handleConfirm}
              disabled={deleting}
              className="border border-[var(--rust)] bg-[var(--rust)] text-white disabled:opacity-60 h-[38px] rounded-btn px-4 text-[13px] font-semibold inline-flex items-center justify-center"
            >
              {deleting ? "Deleting…" : "Delete Role"}
            </button>
          </div>
        </AlertDialogPopup>
      </AlertDialogPortal>
    </AlertDialog>
  )
}
