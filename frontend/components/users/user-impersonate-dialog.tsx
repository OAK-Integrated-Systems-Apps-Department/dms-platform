"use client"

import { UserCog } from "lucide-react"

import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogDescription,
  AlertDialogPopup,
  AlertDialogPortal,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"

import type { UserRecord } from "./types"

interface UserImpersonateDialogProps {
  user: UserRecord
  open: boolean
  onOpenChange: (open: boolean) => void
  onConfirm: () => void
}

/**
 * "Act as User" confirmation - triggers `POST /api/v1/impersonation/start`
 * (see `useImpersonation`). Same "pause before an identity-affecting
 * action" reasoning as `UserResetPasswordDialog`/`UserStatusDialog`: this
 * one temporarily swaps the caller's own effective identity for the whole
 * app, which is at least as consequential as either of those.
 *
 * The confirmation copy is deliberately explicit that this is reversible
 * and auditable - the two things that make impersonation safe to offer at
 * all - rather than reading like an irreversible action (contrast
 * `UserDeleteDialog`'s tone).
 */
export function UserImpersonateDialog({
  user,
  open,
  onOpenChange,
  onConfirm,
}: UserImpersonateDialogProps) {
  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogPortal>
        <AlertDialogPopup className="max-w-[680px]">
          <span className="mx-auto mb-4 flex size-11 items-center justify-center rounded-btn bg-primary/10 text-primary">
            <UserCog size={20} />
          </span>
          <AlertDialogTitle>Act as {user.name}?</AlertDialogTitle>
          <AlertDialogDescription>
            You&apos;ll see and use the application exactly as{" "}
            <strong className="font-semibold text-foreground">
              {user.name}
            </strong>{" "}
            would, with their current permissions. A banner will show this
            session is impersonated the whole time, and every action you
            take is recorded under your own name in the audit log. You can
            return to your own account at any point.
          </AlertDialogDescription>
          <div className="flex items-center justify-center gap-2.5">
            <AlertDialogClose className="border border-[var(--border-strong)] bg-card font-semibold text-foreground h-[38px] rounded-btn px-4 text-[13px] inline-flex items-center justify-center">
              Cancel
            </AlertDialogClose>
            <AlertDialogClose
              onClick={onConfirm}
              className="border border-primary bg-primary text-primary-foreground h-[38px] rounded-btn px-4 text-[13px] font-semibold inline-flex items-center justify-center"
            >
              Act as User
            </AlertDialogClose>
          </div>
        </AlertDialogPopup>
      </AlertDialogPortal>
    </AlertDialog>
  )
}
