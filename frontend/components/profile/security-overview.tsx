"use client"

import { ExternalLink } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { KEYCLOAK_ACCOUNT_URL } from "@/lib/api-config"
import { useCurrentUser } from "@/lib/auth/use-current-user"

import { securitySupplement } from "./data"

/**
 * My Security Settings (S2) — password and MFA status for the signed-in
 * user. Per the wireframe, this isn't a DMS-built change-password/MFA
 * form: both actions deep-link into Keycloak's own hosted Account Console
 * (`KEYCLOAK_ACCOUNT_URL`), which already has that UI. Which self-service
 * actions actually live here vs. purely in Keycloak depends on tenant
 * policy — see the note at the bottom of the screen.
 *
 * Corner radius is fixed at 10px across every surface here (card, badges,
 * buttons), matching the rest of the Self-Service and Role Management
 * screens rather than the app-wide 9px/14px mix used elsewhere.
 */
export function SecurityOverview() {
  const { data: user } = useCurrentUser()

  return (
    <div className="rounded-[10px] border border-border bg-card px-[30px] py-[26px] shadow-[var(--ledger-shadow)]">
      {/* Password */}
      <h2 className="mb-3 font-heading text-[15px] font-semibold">Password</h2>
      <div className="mb-6 grid grid-cols-2 gap-x-6 gap-y-4">
        <div>
          <p className="mb-1 text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
            Last Changed
          </p>
          <p className="font-mono text-[12.5px] text-foreground">
            {securitySupplement.passwordLastChanged}
          </p>
        </div>
        <div>
          <p className="mb-1 text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
            Action
          </p>
          <a
            href={`${KEYCLOAK_ACCOUNT_URL}/#/security/signing-in`}
            target="_blank"
            rel="noreferrer"
            className="inline-flex items-center gap-1.5 text-[13px] font-semibold text-primary hover:underline"
          >
            Change password
            <ExternalLink size={12} />
          </a>
          <span className="ml-2 text-[11px] text-muted-foreground">
            may redirect to Keycloak
          </span>
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
            className="gap-1.5 rounded-[10px]"
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

      <hr className="my-6 border-dashed border-border" />

      <p className="text-[12px] text-muted-foreground">
        The exact set of self-service actions available here depends on what
        tenant policy delegates to DMS vs. handles natively in Keycloak.
      </p>
    </div>
  )
}