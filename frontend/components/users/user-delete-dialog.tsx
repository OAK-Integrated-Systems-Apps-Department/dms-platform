"use client"

import { Trash2 } from "lucide-react"

import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogDescription,
  AlertDialogPopup,
  AlertDialogPortal,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"

import type { UserRecord } from "./types"

interface UserDeleteDialogProps {
  user: UserRecord
  open: boolean
  onOpenChange: (open: boolean) => void
  onConfirm: () => void
}

/**
 * Delete User — confirmation dialog for the User List's Delete row
 * action.
 *
 * Unlike `UserStatusDialog`'s "Deactivate" direction (soft-delete,
 * reversible via Reactivate), this is a genuine, permanent hard delete —
 * `DELETE /api/v1/users/{id}/permanent`, `UserController#purgeUser` — see
 * `deleteUser` in `lib/users/api.ts`. Kept as its own dialog/action
 * rather than folded into `UserStatusDialog` because the two are gated
 * differently: Activate/Deactivate stays available to anyone with
 * `user.manage`, while Delete is additionally restricted to the tenant
 * admin (`UsersTable`'s `canDelete` prop, enforced server-side too) and
 * is always disabled for the TENANT_ADMIN account itself, regardless of
 * who's asking — see `users-table.tsx`.
 */
export function UserDeleteDialog({
  user,
  open,
  onOpenChange,
  onConfirm,
}: UserDeleteDialogProps) {
  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogPortal>
        <AlertDialogPopup className="sm:max-w-2xl">
          <span className="mx-auto mb-4 flex size-11 items-center justify-center rounded-md bg-[var(--rust-tint)] text-[var(--rust)]">
            <Trash2 size={20} aria-hidden="true" />
          </span>
          <AlertDialogTitle>Delete this user?</AlertDialogTitle>
          <AlertDialogDescription>
            <strong className="font-semibold text-foreground">
              {user.name}
            </strong>{" "}
            ({user.username}) will be permanently removed, along with their
            account. This can't be undone — there's no Reactivate for a
            deleted user the way there is for a deactivated one.
          </AlertDialogDescription>
          <div className="flex items-center justify-center gap-2.5">
            <AlertDialogClose className="rounded-md border border-[var(--border-strong)] bg-card px-5 py-2.5 text-[13px] font-semibold text-foreground">
              Cancel
            </AlertDialogClose>
            <AlertDialogClose
              onClick={onConfirm}
              className="rounded-md border border-[var(--rust)] bg-[var(--rust)] px-5 py-2.5 text-[13px] font-bold tracking-[0.02em] text-white"
            >
              Delete User
            </AlertDialogClose>
          </div>
        </AlertDialogPopup>
      </AlertDialogPortal>
    </AlertDialog>
  )
}