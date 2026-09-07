"use client"

import { UserCheck, UserX, type LucideIcon } from "lucide-react"

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
 * Per-direction copy/visuals for the Activate / Deactivate dialog.
 * Keeping this as a lookup (rather than a chain of ternaries inline in the
 * JSX) is what makes this a *single* component for both directions instead
 * of two dialogs to keep in sync — see the module doc below for why that
 * matters for U6.
 */
const STATUS_DIALOG_CONFIG = {
  deactivate: {
    icon: UserX,
    iconClassName: "bg-[var(--rust-tint)] text-[var(--rust)]",
    title: "Deactivate this user?",
    description: (user: UserRecord) => (
      <>
        <strong className="font-semibold text-foreground">{user.name}</strong>{" "}
        will lose access immediately. Their records and
        audit history stay intact, and you can reactivate the account
        anytime from here.
      </>
    ),
    confirmLabel: "Deactivate User",
    confirmClassName: "border-[var(--rust)] bg-[var(--rust)] text-white",
  },
  activate: {
    icon: UserCheck,
    iconClassName: "bg-primary/10 text-primary",
    title: "Activate this user?",
    description: (user: UserRecord) => (
      <>
        <strong className="font-semibold text-foreground">{user.name}</strong>{" "}
         will be able to sign in again right away.
      </>
    ),
    confirmLabel: "Activate User",
    confirmClassName: "border-primary bg-primary text-primary-foreground",
  },
} as const satisfies Record<
  "deactivate" | "activate",
  {
    icon: LucideIcon
    iconClassName: string
    title: string
    description: (user: UserRecord) => React.ReactNode
    confirmLabel: string
    confirmClassName: string
  }
>

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
 * decides which copy and button color render via STATUS_DIALOG_CONFIG above
 * — there's no separate "deactivate dialog" vs "activate dialog" to keep in
 * sync, and no duplicated ternary chains in the JSX.
 */
export function UserStatusDialog({
  user,
  open,
  onOpenChange,
  onConfirm,
}: UserStatusDialogProps) {
  const isDeactivating = user.status === "Active"
  const variant = STATUS_DIALOG_CONFIG[isDeactivating ? "deactivate" : "activate"]
  const Icon = variant.icon

  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogPortal>
        <AlertDialogPopup className="sm:max-w-2xl">
          <span
            className={cn(
              "mx-auto mb-4 flex size-11 items-center justify-center rounded-[10px]",
              variant.iconClassName
            )}
          >
            <Icon size={20} aria-hidden="true" />
          </span>
          <AlertDialogTitle>{variant.title}</AlertDialogTitle>
          <AlertDialogDescription>
            {variant.description(user)}
          </AlertDialogDescription>
          <div className="flex items-center justify-center gap-2.5">
            <AlertDialogClose className="rounded-[10px] border border-[var(--border-strong)] bg-card px-5 py-2.5 text-[13px] font-semibold text-foreground">
              Cancel
            </AlertDialogClose>
            <AlertDialogClose
              onClick={onConfirm}
              className={cn(
                "rounded-[10px] border px-5 py-2.5 text-[13px] font-bold tracking-[0.02em]",
                variant.confirmClassName
              )}
            >
              {variant.confirmLabel}
            </AlertDialogClose>
          </div>
        </AlertDialogPopup>
      </AlertDialogPortal>
    </AlertDialog>
  )
}