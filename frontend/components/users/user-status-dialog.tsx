"use client"

import { UserCheck, UserX } from "lucide-react"

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

interface UserStatusDialogProps {
  user: UserRecord
  open: boolean
  onOpenChange: (open: boolean) => void
  onConfirm: () => void
}

/**
 * Activate / Deactivate User (U6) — confirmation dialog for enabling or
 * disabling a user's account.
 *
 * Deactivating calls `DELETE /api/v1/users/{id}` (a real soft-delete —
 * dms-core marks the row `deleted` and disables the Keycloak account).
 * Activating calls `POST /api/v1/users/{id}/reactivate`, which clears that
 * soft-delete and re-enables the account. The backend deliberately keeps
 * returning a deactivated user from `GET /api/v1/users` and
 * `GET /api/v1/users/{id}` (see UserServiceImpl#listUsers / #getUser)
 * rather than filtering them out, specifically so this dialog's "Activate"
 * direction has a user to act on — otherwise a deactivated user would
 * vanish from the list and this page would 404 on them. See
 * lib/users/api.ts (`deactivateUser` / `reactivateUser`) for where this is
 * actually implemented; this component only renders copy and confirms the
 * click — the caller's `onConfirm` decides which endpoint to hit based on
 * `user.status`.
 *
 * One component handles both directions, matching the wireframe's own
 * note: "same interface pattern is reused for Activate, with inverted copy
 * and a neutral/accent confirm action instead of danger." `user.status`
 * decides which copy and button color render — there's no separate
 * "deactivate dialog" vs "activate dialog" to keep in sync.
 */
export function UserStatusDialog({
  user,
  open,
  onOpenChange,
  onConfirm,
}: UserStatusDialogProps) {
  const isDeactivating = user.status === "Active"
  const Icon = isDeactivating ? UserX : UserCheck

  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogPortal>
        <AlertDialogPopup>
          <span
            className={cn(
              "mx-auto mb-4 flex size-11 items-center justify-center rounded-[10px]",
              isDeactivating
                ? "bg-[var(--rust-tint)] text-[var(--rust)]"
                : "bg-primary/10 text-primary"
            )}
          >
            <Icon size={20} />
          </span>
          <AlertDialogTitle>
            {isDeactivating ? "Deactivate this user?" : "Activate this user?"}
          </AlertDialogTitle>
          <AlertDialogDescription>
            {isDeactivating ? (
              <>
                <strong className="font-semibold text-foreground">
                  {user.name}
                </strong>{" "}
                ({user.username}) will no longer be able to sign in to DMS.
                Historical records and audit history are retained, and the
                account can be reactivated from here later if needed.
              </>
            ) : (
              <>
                <strong className="font-semibold text-foreground">
                  {user.name}
                </strong>{" "}
                ({user.username}) will be able to sign in to DMS again
                immediately.
              </>
            )}
          </AlertDialogDescription>
          <div className="flex items-center justify-center gap-2.5">
            <AlertDialogClose className="rounded-[10px] border border-[var(--border-strong)] bg-card px-5 py-2.5 text-[13px] font-semibold text-foreground">
              Cancel
            </AlertDialogClose>
            <AlertDialogClose
              onClick={onConfirm}
              className={cn(
                "rounded-[10px] border px-5 py-2.5 text-[13px] font-bold tracking-[0.02em]",
                isDeactivating
                  ? "border-[var(--rust)] bg-[var(--rust)] text-white"
                  : "border-primary bg-primary text-primary-foreground"
              )}
            >
              {isDeactivating ? "Deactivate User" : "Activate User"}
            </AlertDialogClose>
          </div>
        </AlertDialogPopup>
      </AlertDialogPortal>
    </AlertDialog>
  )
}