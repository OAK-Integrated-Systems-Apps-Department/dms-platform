"use client"

import { useState } from "react"
import { ExternalLink, KeyRound } from "lucide-react"
import { toast } from "sonner"

import { Badge } from "@/components/ui/badge"
import { LoadingState } from "@/components/ui/loading-state"
import { KEYCLOAK_ACCOUNT_URL } from "@/lib/api-config"
import { requestPasswordReset } from "@/lib/auth/request-password-reset"
import { useCurrentUser } from "@/lib/auth/use-current-user"
import { formatDateTime } from "@/lib/utils"

/**
 * Password and Multi-Factor Authentication sections for My Profile —
 * originally its own `/profile/security` page (My Security Settings, S2),
 * now merged directly into `/profile` below `ProfileOverview`; that
 * standalone route has been removed.
 *
 * "Change password" no longer deep-links into Keycloak's hosted Account
 * Console. It now triggers the same self-service reset flow as
 * `/reset-password` (`requestPasswordReset` — `POST
 * /api/v1/auth/forgot-password`, issuing a PASSWORD_RESET-kind token by
 * email that lands on `/update-password`), using the signed-in user's own
 * known email rather than asking them to type it again. "Manage MFA
 * methods" is unchanged: MFA enrollment itself still isn't a DMS-built
 * flow, so that action still deep-links into Keycloak's own Account
 * Console (`KEYCLOAK_ACCOUNT_URL`).
 *
 * Corner radius is fixed at 10px across every surface here (card, badges,
 * buttons), matching the rest of the Self-Service and Role Management
 * screens rather than the app-wide 9px/14px mix used elsewhere.
 */
export function SecurityOverview() {
  const { data: user, isLoading } = useCurrentUser()
  const [isSendingReset, setIsSendingReset] = useState(false)

  async function handleChangePassword() {
    if (!user?.email) return
    setIsSendingReset(true)
    try {
      await requestPasswordReset(user.email)
      toast.success("Check your email for a link to reset your password.")
    } finally {
      setIsSendingReset(false)
    }
  }

  if (isLoading) {
    return <LoadingState message="Loading…" />
  }

  return (
    <div className="rounded-md border border-border bg-card px-[30px] py-[26px] shadow-[var(--ledger-shadow)]">
      {/* Password */}
      <h2 className="mb-3 font-heading text-[15px] font-semibold">Password</h2>
      <div className="mb-6 grid grid-cols-2 gap-x-6 gap-y-4">
        <div>
          <p className="mb-1 text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
            Last Changed
          </p>
          <p className="font-mono text-[12.5px] text-foreground">
            {user?.lastPasswordChangedAt ? formatDateTime(user.lastPasswordChangedAt) : "—"}
          </p>
        </div>
        <div>
          <p className="mb-1 text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
            Action
          </p>
          <button
            type="button"
            onClick={handleChangePassword}
            disabled={isSendingReset || !user?.email}
            className="inline-flex items-center gap-1.5 text-[13px] font-semibold text-primary hover:underline disabled:cursor-not-allowed disabled:opacity-70"
          >
            <KeyRound size={12} />
            {isSendingReset ? "Sending…" : "Change password"}
          </button>
        </div>
      </div>

      <hr className="my-6 border-dashed border-border" />

      {/* MFA */}
      <h2 className="mb-3 font-heading text-[15px] font-semibold">
        Multi-Factor Authentication
      </h2>
      <div className="grid grid-cols-2 gap-x-6 gap-y-4">
        <div>
          <p className="mb-1 text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
            Status
          </p>
          <Badge
            variant={user?.mfaEnabled ? "default" : "muted"}
            className="gap-1.5 rounded-md"
          >
            <span className="size-[5px] rounded-full bg-current" />
            {user?.mfaEnabled ? "Enrolled" : "Not enrolled"}
          </Badge>
        </div>
        <div>
          <p className="mb-1 text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
            Action
          </p>
          <a
            href={`${KEYCLOAK_ACCOUNT_URL}/#/account-security/signing-in`}
            target="_blank"
            rel="noreferrer"
            className="inline-flex items-center gap-1.5 text-[13px] font-semibold text-primary hover:underline"
          >
            Manage MFA methods
            <ExternalLink size={12} />
          </a>
        </div>
      </div>
    </div>
  )
}
