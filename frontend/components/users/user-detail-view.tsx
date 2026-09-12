import { useState } from "react"
import Link from "next/link"
import { KeyRound } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { getTooltip } from "@/lib/tooltips"

import { renderActivityText } from "./render-activity-text"
import { UserResetPasswordDialog } from "./user-reset-password-dialog"
import { UserStatusDialog } from "./user-status-dialog"
import type { UserRecord } from "./types"

interface UserDetailViewProps {
  user: UserRecord
  onToggleStatus: () => void
  onResetPassword: () => void
  /** Whether the signed-in user may reset this user's password — requires `otua.manage-users.reset-password` (see `usePermissions`). */
  canResetPassword: boolean
  /** Whether the signed-in user may activate/deactivate this account — requires `otua.manage-users.activate-deactivate`. */
  canActivateDeactivate: boolean
  /**
   * Whether the signed-in user may edit this user's profile (the bottom
   * "Edit" link) — requires `otua.manage-users.update`. Someone with only
   * `otua.manage-users.view` can reach this read-only page at all, but
   * gets none of these three controls.
   */
  canEdit: boolean
}

function initials(name: string): string {
  return name
    .split(" ")
    .map((part) => part[0])
    .join("")
    .slice(0, 2)
    .toUpperCase()
}

function InfoItem({
  label,
  value,
  mono,
}: {
  label: string
  value: React.ReactNode
  mono?: boolean
}) {
  return (
    <div className="mb-3 border-b border-dashed border-border pb-3">
      <div className="mb-1.5 text-[10.5px] font-semibold tracking-[0.06em] text-[var(--gold)] uppercase">
        {label}
      </div>
      <div
        className={
          mono
            ? "font-mono text-[13px] text-foreground"
            : "text-sm font-medium text-foreground"
        }
      >
        {value}
      </div>
    </div>
  )
}

function SectionTitle({ children }: { children: React.ReactNode }) {
  return (
    <div className="mb-4 border-b border-border pb-2 text-[11px] font-bold tracking-[0.08em] text-muted-foreground uppercase">
      {children}
    </div>
  )
}

/**
 * View User — per dms-view-user.html. Read-only; "Edit" links to the edit
 * screen. The status control opens the U6 confirmation dialog, and Reset
 * Password opens the U7 confirmation dialog, rather than either acting
 * instantly — same reasoning as `UsersTable`.
 *
 * Reset Password is disabled once `user.status` is "Inactive" — a
 * deactivated account has no active session or login ability to reset a
 * password for. It is NOT gated by `user.verified` — pending (unverified)
 * users can still have a password reset.
 *
 * Activate/Deactivate is always enabled regardless of `verified`.
 */
export function UserDetailView({
  user,
  onToggleStatus,
  onResetPassword,
  canResetPassword,
  canActivateDeactivate,
  canEdit,
}: UserDetailViewProps) {
  const [statusDialogOpen, setStatusDialogOpen] = useState(false)
  const [resetPasswordDialogOpen, setResetPasswordDialogOpen] = useState(false)

  const canResetThisUser = canResetPassword && user.status === "Active"

  return (
    <div className="rounded-lg border border-border bg-card p-[26px] pt-[26px] pb-[28px] shadow-[var(--ledger-shadow)]">
      <div className="mb-[26px] flex items-center gap-4">
        <div className="flex size-14 shrink-0 items-center justify-center rounded-full bg-primary font-heading text-xl font-semibold text-primary-foreground">
          {initials(user.name)}
        </div>
        <div className="flex-1">
          <div className="mb-0.5 flex items-center gap-2 font-heading text-[19px] font-semibold">
            {user.name}
            <Badge
              variant={user.status === "Active" ? "default" : "muted"}
              className="gap-1.5 rounded-md align-middle text-[11px] font-semibold"
            >
              <span className="size-[5px] rounded-full bg-current" />
              {user.status}
            </Badge>
            {!user.verified && (
              <Badge
                variant="gold"
                className="align-middle text-[11px] font-semibold"
              >
                Pending
              </Badge>
            )}
          </div>
          <div className="font-mono text-[12.5px] text-muted-foreground">
            {user.username} &nbsp;·&nbsp; {user.email}
          </div>
        </div>
        <div className="flex shrink-0 items-center gap-2.5">
          {canResetPassword && (
          <Tooltip>
            <TooltipTrigger
              render={(props) => (
                <button
                  {...props}
                  type="button"
                  onClick={() => canResetThisUser && setResetPasswordDialogOpen(true)}
                  disabled={!canResetThisUser}
                  className={
                    canResetThisUser
                      ? "flex items-center gap-1.5 rounded-md border border-[var(--border-strong)] px-4 py-2.5 text-[13px] font-semibold text-foreground hover:border-primary hover:bg-muted hover:text-primary"
                      : "flex cursor-not-allowed items-center gap-1.5 rounded-md border border-[var(--border-strong)] px-4 py-2.5 text-[13px] font-semibold text-muted-foreground opacity-50"
                  }
                >
                  <KeyRound size={14} />
                  Reset Password
                </button>
              )}
            />
            <TooltipContent>
              {canResetThisUser
                ? getTooltip("users.actions.resetPassword")
                : getTooltip("users.pending.resetPasswordDisabled")}
            </TooltipContent>
          </Tooltip>
          )}
          {canActivateDeactivate && (
          <Tooltip>
            <TooltipTrigger
              render={(props) => (
                <button
                  {...props}
                  type="button"
                  onClick={() => setStatusDialogOpen(true)}
                  className={
                    user.status === "Active"
                      ? "rounded-md border border-[var(--rust)] bg-[var(--rust-tint)] px-4 py-2.5 text-[13px] font-semibold text-[var(--rust)]"
                      : "rounded-md border border-primary bg-primary/10 px-4 py-2.5 text-[13px] font-semibold text-primary"
                  }
                >
                  {user.status === "Active" ? "Deactivate User" : "Activate User"}
                </button>
              )}
            />
            <TooltipContent>
              {user.status === "Active"
                ? getTooltip("users.actions.deactivate")
                : getTooltip("users.actions.activate")}
            </TooltipContent>
          </Tooltip>
          )}
        </div>
      </div>

      <div className="mb-[26px]">
        <SectionTitle>Profile</SectionTitle>
        <div className="grid grid-cols-2 gap-x-10">
          <InfoItem
            label="First / last name"
            value={`${user.firstName} ${user.lastName}`}
          />
          <InfoItem label="Tenant" value={user.tenant} />
          <InfoItem
            label="Roles"
            value={
              <div className="flex flex-wrap gap-1.5">
                {user.platformRoles.map((role) => (
                  <Badge
                    key={role}
                    variant="gold"
                    title="Platform role — assigned directly in Keycloak, not a tenant-defined DMS role"
                  >
                    {role}
                  </Badge>
                ))}
                {user.roles.map((role) => (
                  <span
                    key={role}
                    className="rounded-[6px] border border-[var(--border-strong)] bg-muted px-2 py-0.5 text-[11px] font-semibold text-foreground"
                  >
                    {role}
                  </span>
                ))}
                {user.platformRoles.length === 0 && user.roles.length === 0 && (
                  <span className="text-[12px] text-muted-foreground">
                    No roles assigned.
                  </span>
                )}
              </div>
            }
          />
          <InfoItem label="Created on" value={user.createdOn} mono />
        </div>
      </div>

      <div className="mb-[26px]">
        <SectionTitle>Security</SectionTitle>
        <div className="grid grid-cols-2 gap-x-10">
          <InfoItem label="MFA status" value={user.mfaStatus} />
          <InfoItem label="Last login" value={user.lastLogin} mono />
          <InfoItem
            label="Last password change"
            value={user.lastPasswordChange}
            mono
          />
          <InfoItem
            label="Active sessions"
            value={`${user.activeSessions} device${user.activeSessions === 1 ? "" : "s"}`}
          />
        </div>
      </div>

      <div>
        <SectionTitle>Recent activity</SectionTitle>
        {user.recentActivity.length === 0 ? (
          <p className="text-[13px] text-muted-foreground">
            No recent activity.
          </p>
        ) : (
          user.recentActivity.map((entry, i) => (
            <div
              key={entry.id}
              className={
                "flex items-start gap-3.5 pb-3" +
                (i < user.recentActivity.length - 1
                  ? " mb-3 border-b border-dashed border-border"
                  : "")
              }
            >
              <span className="mt-[5px] size-2 shrink-0 rounded-full border-[1.5px] border-[var(--border-strong)]" />
              <span className="w-[150px] shrink-0 font-mono text-xs text-[var(--ink-faint)]">
                {entry.time}
              </span>
              <span className="text-[13.5px] text-foreground">
                {renderActivityText(entry.text)} —{" "}
                <span className="text-[12.5px] text-muted-foreground">
                  {entry.actor}
                </span>
              </span>
            </div>
          ))
        )}
      </div>

      <div className="mt-6 flex flex-wrap items-center justify-between gap-3 border-t border-dashed border-border pt-5">
        <div className="flex flex-wrap items-center gap-4">
          <Link
            href={`/admin/users/${user.id}/access-summary`}
            className="text-[12.5px] font-semibold text-primary hover:underline"
          >
            View effective permissions →
          </Link>
          <Link
            href={`/admin/users/${user.id}/mfa`}
            className="text-[12.5px] font-semibold text-primary hover:underline"
          >
            Manage MFA →
          </Link>
          <Link
            href={`/admin/users/${user.id}/sessions`}
            className="text-[12.5px] font-semibold text-primary hover:underline"
          >
            Manage Sessions →
          </Link>
          <Link
            href={`/admin/users/${user.id}/activity`}
            className="text-[12.5px] font-semibold text-primary hover:underline"
          >
            View Audit History →
          </Link>
        </div>
        {canEdit && (
          <Link
            href={`/admin/users/${user.id}/edit`}
            className="rounded-btn border border-[var(--border-strong)] px-[26px] py-2.5 text-[13px] font-bold tracking-[0.03em] text-foreground hover:border-primary hover:bg-muted hover:text-primary"
          >
            Edit
          </Link>
        )}
      </div>

      <UserStatusDialog
        user={user}
        open={statusDialogOpen}
        onOpenChange={setStatusDialogOpen}
        onConfirm={() => {
          onToggleStatus()
          setStatusDialogOpen(false)
        }}
      />

      <UserResetPasswordDialog
        user={user}
        open={resetPasswordDialogOpen}
        onOpenChange={setResetPasswordDialogOpen}
        onConfirm={() => {
          onResetPassword()
          setResetPasswordDialogOpen(false)
        }}
      />
    </div>
  )
}