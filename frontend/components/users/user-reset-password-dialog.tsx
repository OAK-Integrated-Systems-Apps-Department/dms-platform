"use client"

import { KeyRound } from "lucide-react"

import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogDescription,
  AlertDialogPopup,
  AlertDialogPortal,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"

import type { UserRecord } from "./types"

interface UserResetPasswordDialogProps {
  user: UserRecord
  open: boolean
  onOpenChange: (open: boolean) => void
  onConfirm: () => void
}

/**
 * Reset User Password (U7) — administrator-initiated password reset
 * confirmation. Triggers `POST /api/v1/users/{id}/reset-password`, which
 * calls `PasswordResetService#issueForAdminReset` on the backend: the
 * user gets an emailed link to dms-core's own `/update-password` page
 * (the same custom flow as the self-service "Forgot password?" link on
 * the login page), not a Keycloak-hosted page or a temporary password
 * shown anywhere in DMS.
 *
 * DMS never sets or displays the password itself — this dialog only asks
 * for confirmation before sending that link, same "pause before an
 * account-affecting action" reasoning as `UserStatusDialog` (U6), and
 * built as its own component rather than folded into that one since a
 * password reset isn't a status toggle and always confirms forward (no
 * inverted copy/direction to branch on).
 */
export function UserResetPasswordDialog({
  user,
  open,
  onOpenChange,
  onConfirm,
}: UserResetPasswordDialogProps) {
  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogPortal>
        <AlertDialogPopup className="max-w-[480px]">
          <span className="mx-auto mb-4 flex size-11 items-center justify-center rounded-md bg-primary/10 text-primary">
            <KeyRound size={20} />
          </span>
          <AlertDialogTitle>Reset password for this user?</AlertDialogTitle>
          <AlertDialogDescription>
            <strong className="font-semibold text-foreground">
              {user.name}
            </strong>{" "}
             will receive an email with a link to set a new
            password. DMS does not set or view the password directly.
          </AlertDialogDescription>
          <div className="flex items-center justify-center gap-2.5">
            <AlertDialogClose className="rounded-md border border-[var(--border-strong)] bg-card px-5 py-2.5 text-[13px] font-semibold text-foreground">
              Cancel
            </AlertDialogClose>
            <AlertDialogClose
              onClick={onConfirm}
              className="rounded-md border border-primary bg-primary px-5 py-2.5 text-[13px] font-bold tracking-[0.02em] text-primary-foreground"
            >
              Send Reset
            </AlertDialogClose>
          </div>
        </AlertDialogPopup>
      </AlertDialogPortal>
    </AlertDialog>
  )
}