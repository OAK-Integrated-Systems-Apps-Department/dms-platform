"use client"

import { LogOut, ShieldX } from "lucide-react"

import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogDescription,
  AlertDialogPopup,
  AlertDialogPortal,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"

import type { UserRecord, UserSessionEntry } from "./types"

export type RevokeSessionTarget =
  { kind: "one"; session: UserSessionEntry } | { kind: "all" }

interface RevokeSessionDialogProps {
  user: UserRecord
  target: RevokeSessionTarget
  open: boolean
  onOpenChange: (open: boolean) => void
  onConfirm: () => void
}

/**
 * User Sessions (U9) — confirmation dialog for revoking either one session
 * or every session:
 *   `DELETE /admin/realms/{realm}/sessions/{sessionId}` (revoke one)
 *   `POST /admin/realms/{realm}/users/{id}/logout` (revoke all)
 *
 * One component, `target.kind` picks the copy/icon — same "one dialog,
 * two directions" shape as `UserStatusDialog` (U6) and
 * `UserMfaActionDialog` (U8). Always destructive/rust-styled (unlike
 * those two), since both directions here immediately sign a device out —
 * there's no non-destructive branch to give primary styling to.
 */
export function RevokeSessionDialog({
  user,
  target,
  open,
  onOpenChange,
  onConfirm,
}: RevokeSessionDialogProps) {
  const isAll = target.kind === "all"
  const Icon = isAll ? ShieldX : LogOut

  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogPortal>
        <AlertDialogPopup>
          <span className="mx-auto mb-4 flex size-11 items-center justify-center rounded-md bg-[var(--rust-tint)] text-[var(--rust)]">
            <Icon size={20} />
          </span>
          <AlertDialogTitle>
            {isAll
              ? "Revoke all sessions for this user?"
              : "Revoke this session?"}
          </AlertDialogTitle>
          <AlertDialogDescription>
            {isAll ? (
              <>
                Every active session for{" "}
                <strong className="font-semibold text-foreground">
                  {user.name}
                </strong>{" "}
                ({user.username}) will be signed out immediately. They will need
                to sign in again on each device.
              </>
            ) : (
              <>
                <strong className="font-semibold text-foreground">
                  {target.session.device}
                </strong>{" "}
                ({target.session.ip}) will be signed out immediately.
              </>
            )}
          </AlertDialogDescription>
          <div className="flex items-center justify-center gap-2.5">
            <AlertDialogClose className="border border-[var(--border-strong)] bg-card font-semibold text-foreground h-[38px] rounded-btn px-4 text-[13px] inline-flex items-center justify-center">
              Cancel
            </AlertDialogClose>
            <AlertDialogClose
              onClick={onConfirm}
              className="border border-[var(--rust)] bg-[var(--rust)] text-white h-[38px] rounded-btn px-4 text-[13px] font-semibold inline-flex items-center justify-center"
            >
              {isAll ? "Revoke All Sessions" : "Revoke Session"}
            </AlertDialogClose>
          </div>
        </AlertDialogPopup>
      </AlertDialogPortal>
    </AlertDialog>
  )
}
