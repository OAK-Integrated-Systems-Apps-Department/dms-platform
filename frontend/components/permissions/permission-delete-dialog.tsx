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
 * server-side). The backend now rejects this while any role still holds
 * the permission (see the guard added to
 * `PermissionServiceImpl.deletePermission`), so the error banner here is
 * often exactly that message rather than a generic failure.
 *
 * Same shape as `components/roles/role-delete-dialog.tsx`: the confirm
 * button is a plain `<button>`, not an `AlertDialogClose`, because the
 * delete is async and can fail — `AlertDialogClose` always closes on
 * click regardless of outcome, which would hide that failure instead of
 * showing it.
 */
export function PermissionDeleteDialog({
  permission,
  open,
  onOpenChange,
  onConfirm,
}: PermissionDeleteDialogProps) {
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
        <AlertDialogPopup className="max-w-[740px]">
          <span className="mx-auto mb-4 flex size-11 items-center justify-center rounded-[10px] bg-[var(--rust-tint)] text-[var(--rust)]">
            <Trash2 size={20} />
          </span>
          <AlertDialogTitle>Delete this permission?</AlertDialogTitle>
          <AlertDialogDescription>
            <strong className="font-semibold text-foreground">{permission.name}</strong>{" "}
            removed from the catalog. This can&apos;t be undone, and it can&apos;t be
            done while any role still holds it.
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
              {deleting ? "Deleting…" : "Delete Permission"}
            </button>
          </div>
        </AlertDialogPopup>
      </AlertDialogPortal>
    </AlertDialog>
  )
}