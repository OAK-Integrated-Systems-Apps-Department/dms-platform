"use client"

import { ShieldAlert, ShieldOff } from "lucide-react"

import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogDescription,
  AlertDialogPopup,
  AlertDialogPortal,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { cn } from "@/lib/utils"

import type { UserRecord } from "./types"

export type UserMfaAction = "require-setup" | "reset-credentials"

interface UserMfaActionDialogProps {
  user: UserRecord
  action: UserMfaAction
  open: boolean
  onOpenChange: (open: boolean) => void
  onConfirm: () => void
}

/**
 * User MFA Management (U8) — confirmation dialog for the two admin actions
 * on that screen:
 *   `PUT /admin/realms/{realm}/users/{id}` body `{ requiredActions: ["CONFIGURE_TOTP"] }`
 *   `DELETE /admin/realms/{realm}/users/{id}/credentials/{credentialId}` (reset MFA)
 *
 * One component, `action` prop picks the copy/icon/color — same reasoning
 * as `UserStatusDialog` (U6) covering both Activate and Deactivate, rather
 * than two near-duplicate dialog components. "Require setup" is the
 * non-destructive direction (primary styling, same as Activate); "reset
 * credentials" revokes an existing enrollment (rust/destructive styling,
 * same as Deactivate).
 */
export function UserMfaActionDialog({
  user,
  action,
  open,
  onOpenChange,
  onConfirm,
}: UserMfaActionDialogProps) {
  const isReset = action === "reset-credentials"
  const Icon = isReset ? ShieldOff : ShieldAlert

  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogPortal>
        <AlertDialogPopup>
          <span
            className={cn(
              "mx-auto mb-4 flex size-11 items-center justify-center rounded-md",
              isReset
                ? "bg-[var(--rust-tint)] text-[var(--rust)]"
                : "bg-primary/10 text-primary"
            )}
          >
            <Icon size={20} />
          </span>
          <AlertDialogTitle>
            {isReset
              ? "Reset MFA credentials for this user?"
              : "Require MFA setup for this user?"}
          </AlertDialogTitle>
          <AlertDialogDescription>
            {isReset ? (
              <>
                <strong className="font-semibold text-foreground">
                  {user.name}
                </strong>{" "}
                ({user.username}) will lose their enrolled authenticator.
                They&apos;ll need to enroll a new method before their next
                sign-in that requires MFA.
              </>
            ) : (
              <>
                <strong className="font-semibold text-foreground">
                  {user.name}
                </strong>{" "}
                ({user.username}) will be required to configure multi-factor
                authentication the next time they sign in.
              </>
            )}
          </AlertDialogDescription>
          <div className="flex items-center justify-center gap-2.5">
            <AlertDialogClose className="rounded-md border border-[var(--border-strong)] bg-card px-5 py-2.5 text-[13px] font-semibold text-foreground">
              Cancel
            </AlertDialogClose>
            <AlertDialogClose
              onClick={onConfirm}
              className={cn(
                "rounded-md border px-5 py-2.5 text-[13px] font-bold tracking-[0.02em]",
                isReset
                  ? "border-[var(--rust)] bg-[var(--rust)] text-white"
                  : "border-primary bg-primary text-primary-foreground"
              )}
            >
              {isReset ? "Reset Credentials" : "Require Setup"}
            </AlertDialogClose>
          </div>
        </AlertDialogPopup>
      </AlertDialogPortal>
    </AlertDialog>
  )
}
