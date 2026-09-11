"use client"

import { useState } from "react"
import Link from "next/link"
import { LogOut, Monitor } from "lucide-react"

import {
  RevokeSessionDialog,
  type RevokeSessionTarget,
} from "./user-revoke-session-dialog"
import type { UserRecord, UserSessionEntry } from "./types"

interface UserSessionsProps {
  user: UserRecord
  onRevokeSession: (sessionId: string) => void
  onRevokeAllSessions: () => void
  /** Whether the signed-in user may revoke sessions — requires `user.manage` (see `usePermissions`). */
  canManage: boolean
}

function SessionRow({
  session,
  onRevoke,
  canManage,
}: {
  session: UserSessionEntry
  onRevoke: () => void
  canManage: boolean
}) {
  return (
    <div className="flex items-center justify-between gap-3 border-b border-dashed border-border py-3 last:border-b-0">
      <div className="flex items-center gap-3">
        <span className="flex size-9 shrink-0 items-center justify-center rounded-[10px] bg-muted text-muted-foreground">
          <Monitor size={16} />
        </span>
        <div>
          <div className="text-[13px] font-semibold text-foreground">
            {session.device}
          </div>
          <div className="font-mono text-[11px] text-muted-foreground">
            IP: {session.ip} · Started: {session.startedAt} · Location:{" "}
            {session.location}
          </div>
        </div>
      </div>
      {canManage && (
        <button
          type="button"
          onClick={onRevoke}
          className="flex shrink-0 items-center gap-1.5 rounded-[10px] border border-[var(--rust)] bg-[var(--rust-tint)] px-3.5 py-2 text-[12.5px] font-semibold text-[var(--rust)]"
        >
          <LogOut size={13} />
          Revoke
        </button>
      )}
    </div>
  )
}

/**
 * User Sessions (U9) — active login sessions for a user, with per-session
 * revoke and a revoke-all action:
 *   `GET /admin/realms/{realm}/users/{id}/sessions`
 *   `DELETE /admin/realms/{realm}/sessions/{sessionId}` (revoke one)
 *   `POST /admin/realms/{realm}/users/{id}/logout` (revoke all)
 *
 * Same "screen card" shape as `UserMfaManagement` (U8) and
 * `UserAccessSummary` (A1) — identity header, then the feature content,
 * all in one `rounded-[10px]` card. Both revoke actions route through
 * `RevokeSessionDialog` rather than firing instantly, same reasoning as
 * every other account-affecting action in this feature (U6/U7/U8) — a
 * revoke signs a real device out immediately and can't be undone from
 * here.
 */
export function UserSessions({
  user,
  onRevokeSession,
  onRevokeAllSessions,
  canManage,
}: UserSessionsProps) {
  const [pendingTarget, setPendingTarget] =
    useState<RevokeSessionTarget | null>(null)

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

      <hr className="mb-2 border-dashed border-border" />

      {/* Active sessions */}
      <div className="mb-2 flex items-center justify-between pt-4">
        <h3 className="text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
          Active Sessions
        </h3>
        <span className="text-[12px] text-muted-foreground">
          {user.sessions.length} device
          {user.sessions.length === 1 ? "" : "s"}
        </span>
      </div>

      {user.sessions.length === 0 ? (
        <p className="py-3 text-[13px] text-muted-foreground">
          No active sessions.
        </p>
      ) : (
        <div>
          {user.sessions.map((session) => (
            <SessionRow
              key={session.id}
              session={session}
              onRevoke={() => setPendingTarget({ kind: "one", session })}
              canManage={canManage}
            />
          ))}
        </div>
      )}

      <hr className="my-6 border-dashed border-border" />

      {canManage && (
        <div className="flex justify-end">
          <button
            type="button"
            onClick={() => setPendingTarget({ kind: "all" })}
            disabled={user.sessions.length === 0}
            className="rounded-[10px] border border-[var(--rust)] bg-[var(--rust)] px-5 py-2.5 text-[13px] font-bold tracking-[0.02em] text-white disabled:cursor-not-allowed disabled:opacity-50"
          >
            Revoke All Sessions
          </button>
        </div>
      )}

      {pendingTarget && (
        <RevokeSessionDialog
          user={user}
          target={pendingTarget}
          open={pendingTarget !== null}
          onOpenChange={(open) => {
            if (!open) setPendingTarget(null)
          }}
          onConfirm={() => {
            if (pendingTarget.kind === "one") {
              onRevokeSession(pendingTarget.session.id)
            } else {
              onRevokeAllSessions()
            }
            setPendingTarget(null)
          }}
        />
      )}
    </div>
  )
}
