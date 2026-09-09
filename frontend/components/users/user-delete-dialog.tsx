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
 * Delete User — confirmation dialog for the User List's new Delete row
 * action.
 *
 * Calls the same `DELETE /api/v1/users/{id}` endpoint `UserStatusDialog`'s
 * "Deactivate" direction already uses (dms-core has no separate hard-delete
 * — see UserController#deactivateUser) — see `deleteUser` in
 * `lib/users/use-users.ts`. Kept as its own dialog/action rather than
 * folded into `UserStatusDialog` because the two are gated differently:
 * Activate/Deactivate stays available to anyone with `user.manage`, while
 * Delete is additionally restricted to the tenant admin
 * (`UsersTable`'s `canDelete` prop) and is always disabled for the
 * TENANT_ADMIN account itself, regardless of who's asking — see
 * `users-table.tsx`.
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
          <span className="mx-auto mb-4 flex size-11 items-center justify-center rounded-[10px] bg-[var(--rust-tint)] text-[var(--rust)]">
            <Trash2 size={20} aria-hidden="true" />
          </span>
          <AlertDialogTitle>Delete this user?</AlertDialogTitle>
          <AlertDialogDescription>
            <strong className="font-semibold text-foreground">
              {user.name}
            </strong>{" "}
            ({user.username}) will lose access immediately. Their records and
            audit history stay intact, and an admin can restore the account
            from here afterward.
          </AlertDialogDescription>
          <div className="flex items-center justify-center gap-2.5">
            <AlertDialogClose className="rounded-[10px] border border-[var(--border-strong)] bg-card px-5 py-2.5 text-[13px] font-semibold text-foreground">
              Cancel
            </AlertDialogClose>
            <AlertDialogClose
              onClick={onConfirm}
              className="rounded-[10px] border border-[var(--rust)] bg-[var(--rust)] px-5 py-2.5 text-[13px] font-bold tracking-[0.02em] text-white"
            >
              Delete User
            </AlertDialogClose>
          </div>
        </AlertDialogPopup>
      </AlertDialogPortal>
    </AlertDialog>
  )
}