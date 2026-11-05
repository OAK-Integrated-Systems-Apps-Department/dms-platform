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

import type { PermissionRecord } from "./types"

interface PermissionDeleteDialogProps {
  permission: PermissionRecord
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Performs the delete and resolves with an error message, or null on success. */
  onConfirm: () => Promise<string | null>
}

/**
 * Delete Permission — confirmation dialog for retiring a permission from
 * the catalog (`DELETE /api/v1/permissions/{id}`, a soft-delete
 * server-side). The backend rejects this while any role still holds the
 * permission (see the guard in `PermissionServiceImpl.deletePermission`);
 * that rejection surfaces to the user as a toast, not inline here.
 *
 * Same shape as `components/roles/role-delete-dialog.tsx`: the confirm
 * button is a plain `<button>`, not an `AlertDialogClose`, because the
 * delete is async and can fail — `AlertDialogClose` always closes on
 * click regardless of outcome, which would hide that failure instead of
 * leaving the dialog open. The failure message itself is surfaced by the
 * caller via a toast rather than repeated here.
 */
export function PermissionDeleteDialog({
  permission,
  open,
  onOpenChange,
  onConfirm,
}: PermissionDeleteDialogProps) {
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
        <AlertDialogPopup className="max-w-[740px]">
          <span className="mx-auto mb-4 flex size-11 items-center justify-center rounded-btn bg-[var(--rust-tint)] text-[var(--rust)]">
            <Trash2 size={20} />
          </span>
          <AlertDialogTitle>Delete this permission?</AlertDialogTitle>
          <AlertDialogDescription>
            <strong className="font-semibold text-foreground">{permission.name}</strong>{" "}
            removed from the catalog. This can&apos;t be undone, and it can&apos;t be
            done while any role still holds it.
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
              {deleting ? "Deleting…" : "Delete Permission"}
            </button>
          </div>
        </AlertDialogPopup>
      </AlertDialogPortal>
    </AlertDialog>
  )
}