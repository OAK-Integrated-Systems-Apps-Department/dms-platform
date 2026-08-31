"use client"

import { useState } from "react"
import Link from "next/link"
import { ShieldAlert, ShieldOff } from "lucide-react"

import { Badge } from "@/components/ui/badge"

import {
  UserMfaActionDialog,
  type UserMfaAction,
} from "./user-mfa-action-dialog"
import type { UserRecord } from "./types"

interface UserMfaManagementProps {
  user: UserRecord
  onRequireSetup: () => void
  onResetCredentials: () => void
}

function MfaKvItem({
  label,
  value,
}: {
  label: string
  value: React.ReactNode
}) {
  return (
    <div className="rounded-[10px] border border-border bg-muted px-4 py-3">
      <div className="mb-1 text-[10.5px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
        {label}
      </div>
      <div className="text-[13.5px] font-medium text-foreground">{value}</div>
    </div>
  )
}

/**
 * User MFA Management (U8) — view a user's MFA enrollment status and run
 * the two permitted administrative actions:
 *   `GET /admin/realms/{realm}/users/{id}/credentials`
 *   `DELETE .../credentials/{credentialId}` (reset MFA)
 *   `PUT /admin/realms/{realm}/users/{id}` body `{ requiredActions: ["CONFIGURE_TOTP"] }`
 *
 * Read-only status + action buttons, same split as `UserAccessSummary`
 * (A1): a status/info card up top, actions below, both wrapped in a single
 * `rounded-[10px]` card. Both actions route through `UserMfaActionDialog`
 * (U8's confirmation step) rather than firing instantly — resetting
 * credentials removes a real login factor and requiring setup forces a
 * re-enrollment flow, so neither should be a one-click accident, same
 * reasoning as `UserStatusDialog` (U6) and `UserResetPasswordDialog` (U7).
 *
 * Per the wireframe note, these actions are "typically restricted to
 * users managing administrator accounts" — that authorization check
 * belongs to the route/permission layer, not this component.
 */
export function UserMfaManagement({
  user,
  onRequireSetup,
  onResetCredentials,
}: UserMfaManagementProps) {
  const [pendingAction, setPendingAction] = useState<UserMfaAction | null>(null)

  return (
    <div className="rounded-[10px] border border-border bg-card px-[30px] py-[26px] shadow-[var(--ledger-shadow)]">
      {/* Identity */}
      <div className="mb-6 flex items-center justify-between gap-3">
        <div>
          <p className="mb-0.5 font-heading text-[17px] font-semibold">
            {user.name}
          </p>
          <p className="font-mono text-[12px] text-muted-foreground">
            {user.username} · {user.email}
          </p>
        </div>
        <Link
          href={`/admin/users/${user.id}`}
          className="text-[12px] font-semibold text-primary hover:underline"
        >
          View full profile →
        </Link>
      </div>

      <hr className="mb-6 border-dashed border-border" />

      {/* MFA status */}
      <div className="mb-6">
        <div className="mb-2.5 flex items-center justify-between">
          <h3 className="text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
            MFA Status
          </h3>
          <Badge
            variant={user.mfaEnrolled ? "default" : "muted"}
            className="gap-1.5 rounded-[10px]"
          >
            <span className="size-[5px] rounded-full bg-current" />
            {user.mfaEnrolled ? "Enrolled" : "Not Enrolled"}
          </Badge>
        </div>

        <div className="grid grid-cols-2 gap-3">
          <MfaKvItem
            label="Registered Method(s)"
            value={
              user.mfaMethods.length > 0 ? user.mfaMethods.join(", ") : "None"
            }
          />
          <MfaKvItem label="Enrolled On" value={user.mfaEnrolledOn ?? "—"} />
          <MfaKvItem
            label="Required by Policy"
            value={
              <Badge
                variant={user.mfaRequiredByPolicy ? "gold" : "outline"}
                className="rounded-[10px]"
              >
                {user.mfaRequiredByPolicy
                  ? "Required for this role"
                  : "Not required"}
              </Badge>
            }
          />
        </div>
      </div>

      {/* Administrative actions */}
      <div>
        <h3 className="mb-2.5 text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
          Administrative Actions
        </h3>
        <div className="flex flex-wrap gap-2.5">
          <button
            type="button"
            onClick={() => setPendingAction("require-setup")}
            className="flex items-center gap-1.5 rounded-[10px] border border-[var(--border-strong)] bg-card px-4 py-2.5 text-[13px] font-semibold text-foreground hover:border-primary hover:bg-muted hover:text-primary"
          >
            <ShieldAlert size={14} />
            Require MFA Setup
          </button>
          <button
            type="button"
            onClick={() => setPendingAction("reset-credentials")}
            disabled={!user.mfaEnrolled}
            className="flex items-center gap-1.5 rounded-[10px] border border-[var(--border-strong)] bg-card px-4 py-2.5 text-[13px] font-semibold text-foreground hover:border-primary hover:bg-muted hover:text-primary disabled:cursor-not-allowed disabled:opacity-50 disabled:hover:border-[var(--border-strong)] disabled:hover:bg-card disabled:hover:text-foreground"
          >
            <ShieldOff size={14} />
            Reset MFA Credentials
          </button>
        </div>
        <p className="mt-3.5 text-[12px] text-muted-foreground">
          Actions available here depend on final Keycloak MFA configuration and
          are typically restricted to users managing administrator accounts.
        </p>
      </div>

      {pendingAction && (
        <UserMfaActionDialog
          user={user}
          action={pendingAction}
          open={pendingAction !== null}
          onOpenChange={(open) => {
            if (!open) setPendingAction(null)
          }}
          onConfirm={() => {
            if (pendingAction === "require-setup") onRequireSetup()
            else onResetCredentials()
            setPendingAction(null)
          }}
        />
      )}
    </div>
  )
}
