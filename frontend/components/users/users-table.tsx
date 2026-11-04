"use client"

import { useState } from "react"
import { Eye, KeyRound, Pencil, Trash2, UserCheck, UserCog, UserX } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { SortableTh } from "@/components/ui/sortable-th"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { useSortableTable } from "@/hooks/use-sortable-table"
import { getTooltip } from "@/lib/tooltips"
import { cn } from "@/lib/utils"

import { UserDeleteDialog } from "./user-delete-dialog"
import { UserImpersonateDialog } from "./user-impersonate-dialog"
import { UserResetPasswordDialog } from "./user-reset-password-dialog"
import { UserStatusDialog } from "./user-status-dialog"
import type { UserRecord } from "./types"

interface UsersTableProps {
  users: UserRecord[]
  onToggleStatus: (id: string, reason?: string) => void
  onResetPassword: (id: string) => void
  onDelete: (id: string) => void
  onView: (user: UserRecord) => void
  onEdit: (user: UserRecord) => void
  /** Starts an "Act as User" session for this row - requires `otua.manage-users.impersonate` (`canImpersonate`). */
  onImpersonate: (id: string) => void

  /** Whether the signed-in user may edit another account's profile — requires `otua.manage-users.update` (see `usePermissions`). Hidden entirely rather than shown disabled, so a view-only user never sees a button that would just 403 on click. */
  canEdit: boolean

  /**
   * Whether the signed-in user may change another account's role
   * assignments — requires `otua.manage-users.assign-role`. The Edit
   * icon below opens the same Edit User form either way; `UserEditForm`
   * itself only submits the fields the caller actually holds permission
   * for (see that component's own doc comment), so a caller with only
   * this permission and not `canEdit` still reaches a usable form rather
   * than a dead end or a partial-403 on save.
   */
  canAssignRole: boolean

  /** Whether the signed-in user may reset another account's password — requires `otua.manage-users.reset-password`. */
  canResetPassword: boolean

  /**
   * Whether the signed-in user may reactivate a currently-inactive
   * account — requires `otua.manage-users.activate`. Independent of
   * `canDeactivate`: a role can hold one without the other (see
   * `PermissionCodes.OTUA_USERS_ACTIVATE`'s own doc comment for why
   * these were split from one combined permission).
   */
  canActivate: boolean

  /**
   * Whether the signed-in user may deactivate a currently-active account —
   * requires `otua.manage-users.deactivate`.
   */
  canDeactivate: boolean

  /**
   * Whether the signed-in user may delete other accounts at all — either
   * the tenant admin (see `lib/auth/use-is-tenant-admin.ts`, which always
   * can, unconditionally) or anyone granted `otua.manage-users.delete`.
   * The Delete column is left out entirely for everyone else, rather than
   * shown disabled, since it isn't a capability they can ever use.
   */
  canDelete: boolean

  /**
   * Whether the signed-in user may impersonate another account —
   * requires `otua.manage-users.impersonate`. Hidden for the caller's own
   * row (impersonating yourself is a no-op the backend rejects anyway) and
   * disabled for an inactive account, matching Reset Password's own
   * active-only rule.
   */
  canImpersonate: boolean

  /** The signed-in user's own id, to exclude their row from the Act as User action. */
  currentUserId: string | undefined
}

type SortKey = "name" | "username" | "email" | "status" | "lastLogin"

/**
 * Name / Username / Email / Role(s) / Status / Last login / Actions — per
 * dms-admin-users.html. Status changes always go through the confirmation
 * dialog (U6 / `UserStatusDialog`), and password resets go through U7 /
 * `UserResetPasswordDialog` — there's no instant action here, since both
 * affect a real person's account and shouldn't be one-click accidents.
 *
 * Reset Password is disabled once `user.status` is "Inactive" — a
 * deactivated account has no active session or login ability to reset a
 * password for. It is NOT gated by `user.verified` anymore — pending
 * (unverified) users can still have a password reset.
 *
 * Activate/Deactivate is always enabled regardless of `verified`.
 *
 * Delete only renders for callers with `canDelete` (the tenant admin —
 * see `lib/auth/use-is-tenant-admin.ts`), and is disabled for the
 * TENANT_ADMIN account's own row even then, so the tenant can't be left
 * without an admin. Goes through `UserDeleteDialog` the same way status
 * changes go through `UserStatusDialog` — no one-click delete either.
 */
export function UsersTable({
  users,
  onToggleStatus,
  onResetPassword,
  onDelete,
  onView,
  onEdit,
  onImpersonate,
  canEdit,
  canAssignRole,
  canResetPassword,
  canActivate,
  canDeactivate,
  canDelete,
  canImpersonate,
  currentUserId,
}: UsersTableProps) {
  const [pendingUser, setPendingUser] = useState<UserRecord | null>(null)
  const [pendingResetUser, setPendingResetUser] = useState<UserRecord | null>(
    null
  )
  const [pendingDeleteUser, setPendingDeleteUser] = useState<UserRecord | null>(
    null
  )
  const [pendingImpersonateUser, setPendingImpersonateUser] =
    useState<UserRecord | null>(null)

  const { sorted, sortKey, direction, toggleSort } = useSortableTable<
    UserRecord,
    SortKey
  >(users, {
    name: (u) => u.name,
    username: (u) => u.username,
    email: (u) => u.email,
    status: (u) => u.status,
    lastLogin: (u) => u.lastLogin,
  })

  return (
    <div className="overflow-hidden rounded-lg border border-border bg-card shadow-[var(--ledger-shadow)]">
      <div className="overflow-x-auto">
        <table className="w-full min-w-[860px] border-collapse">
          <thead>
            <tr className="border-b border-border">
              <SortableTh
                label="Name"
                active={sortKey === "name"}
                direction={direction}
                onClick={() => toggleSort("name")}
              />
              <SortableTh
                label="Username"
                active={sortKey === "username"}
                direction={direction}
                onClick={() => toggleSort("username")}
              />
              <SortableTh
                label="Email"
                active={sortKey === "email"}
                direction={direction}
                onClick={() => toggleSort("email")}
              />
              <th className="px-3 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
                Role(s)
              </th>
              <SortableTh
                label="Status"
                active={sortKey === "status"}
                direction={direction}
                onClick={() => toggleSort("status")}
              />
              <SortableTh
                label="Last login"
                active={sortKey === "lastLogin"}
                direction={direction}
                onClick={() => toggleSort("lastLogin")}
              />
              <th className="px-3 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
                Actions
              </th>
            </tr>
          </thead>
          <tbody>
            {users.length === 0 && (
              <tr>
                <td
                  colSpan={7}
                  className="h-[max(160px,calc(100svh-380px))] px-3 py-10 text-center text-[13px] text-muted-foreground"
                >
                  No users match your search or filters.
                </td>
              </tr>
            )}
            {sorted.map((user) => (
              <tr
                key={user.id}
                className="border-b border-border last:border-b-0 hover:bg-muted"
              >
                <td className="px-3 py-3 text-[13.5px] font-semibold text-primary">
                  {user.name}
                </td>
                <td className="px-3 py-3 font-mono text-[12.5px] text-muted-foreground">
                  {user.username}
                </td>
                <td className="px-3 py-3 text-[13px] text-muted-foreground">
                  <div className="flex items-center gap-1.5">
                    {user.email}
                    {!user.verified && (
                      <Badge variant="gold" className="text-[10px] font-semibold">
                        Pending
                      </Badge>
                    )}
                  </div>
                </td>
                <td className="px-3 py-3">
                  <div className="flex flex-wrap gap-1">
                    {user.platformRoles.map((role) => (
                      <Badge
                        key={role}
                        variant="gold"
                      >
                        {role}
                      </Badge>
                    ))}
                    {user.roles.map((role) => (
                      <span
                        key={role}
                        className="rounded-[6px] border border-[var(--border-strong)] bg-muted px-2 py-0.5 text-[11px] font-semibold whitespace-nowrap text-foreground"
                      >
                        {role}
                      </span>
                    ))}
                    {user.platformRoles.length === 0 && user.roles.length === 0 && (
                      <span className="text-[12px] text-muted-foreground">—</span>
                    )}
                  </div>
                </td>
                <td className="px-3 py-3">
                  <Badge
                    variant={user.status === "Active" ? "default" : "muted"}
                    className="gap-1.5 rounded-md"
                  >
                    <span className="size-[5px] rounded-full bg-current" />
                    {user.status}
                  </Badge>
                </td>
                <td className="px-3 py-3 font-mono text-[12.5px] text-muted-foreground">
                  {user.lastLogin}
                </td>
                <td className="px-3 py-3">
                  <div className="flex items-center gap-1">
                    <Tooltip>
                      <TooltipTrigger
                        render={(props) => (
                          <button
                            {...props}
                            type="button"
                            onClick={() => onView(user)}
                            aria-label={`View ${user.name}`}
                            className="flex items-center justify-center text-primary transition-colors hover:bg-muted size-8 rounded-btn"
                          >
                            <Eye size={15} />
                          </button>
                        )}
                      />
                      <TooltipContent>{getTooltip("users.actions.view")}</TooltipContent>
                    </Tooltip>

                    {(canEdit || canAssignRole) && (
                      <Tooltip>
                        <TooltipTrigger
                          render={(props) => (
                            <button
                              {...props}
                              type="button"
                              onClick={() => onEdit(user)}
                              aria-label={`Edit ${user.name}`}
                              className="flex items-center justify-center text-primary transition-colors hover:bg-muted size-8 rounded-btn"
                            >
                              <Pencil size={15} />
                            </button>
                          )}
                        />
                        <TooltipContent>{getTooltip("users.actions.edit")}</TooltipContent>
                      </Tooltip>
                    )}

                    {canResetPassword &&
                      (() => {
                        const canResetThisUser = user.status === "Active"
                        return (
                          <Tooltip>
                            <TooltipTrigger
                              render={(props) => (
                                <button
                                  {...props}
                                  type="button"
                                  onClick={() =>
                                    canResetThisUser && setPendingResetUser(user)
                                  }
                                  disabled={!canResetThisUser}
                                  aria-label={`Reset password for ${user.name}`}
                                  className={cn(
                                    "flex size-7 items-center justify-center rounded-sm transition-colors",
                                    canResetThisUser
                                      ? "text-primary hover:bg-muted"
                                      : "cursor-not-allowed text-muted-foreground opacity-50"
                                  )}
                                >
                                  <KeyRound size={15} />
                                </button>
                              )}
                            />
                            <TooltipContent>
                              {canResetThisUser
                                ? getTooltip("users.actions.resetPassword")
                                : getTooltip("users.pending.resetPasswordDisabled")}
                            </TooltipContent>
                          </Tooltip>
                        )
                      })()}

                    {(user.status === "Active" ? canDeactivate : canActivate) &&
                      (() => {
                        // Blanket rule, same as Delete just below — a
                        // tenant admin account can't be deactivated
                        // either, regardless of how many other tenant
                        // admins exist (see UserServiceImpl#deactivateUser's
                        // own doc comment on the backend for why this
                        // isn't a "last admin" headcount). Only blocks the
                        // Deactivate direction — reactivating an already-
                        // inactive tenant admin is unaffected.
                        const blockDeactivate =
                          user.status === "Active" &&
                          user.platformRoles.includes("TENANT_ADMIN")
                        return (
                          <Tooltip>
                            <TooltipTrigger
                              render={(props) => (
                                <button
                                  {...props}
                                  type="button"
                                  onClick={() =>
                                    !blockDeactivate && setPendingUser(user)
                                  }
                                  disabled={blockDeactivate}
                                  aria-label={
                                    user.status === "Active"
                                      ? `Deactivate ${user.name}`
                                      : `Activate ${user.name}`
                                  }
                                  className={cn(
                                    "flex size-7 items-center justify-center rounded-sm transition-colors",
                                    blockDeactivate
                                      ? "cursor-not-allowed text-muted-foreground opacity-50"
                                      : user.status === "Active"
                                        ? "text-[var(--rust)] hover:bg-[var(--rust-tint)]"
                                        : "text-primary hover:bg-muted"
                                  )}
                                >
                                  {user.status === "Active" ? (
                                    <UserX size={15} />
                                  ) : (
                                    <UserCheck size={15} />
                                  )}
                                </button>
                              )}
                            />
                            <TooltipContent>
                              {blockDeactivate
                                ? getTooltip("users.tenantAdmin.deactivateDisabled")
                                : user.status === "Active"
                                  ? getTooltip("users.actions.deactivate")
                                  : getTooltip("users.actions.activate")}
                            </TooltipContent>
                          </Tooltip>
                        )
                      })()}

                    {canImpersonate &&
                      user.id !== currentUserId &&
                      (() => {
                        const canImpersonateThisUser = user.status === "Active"
                        return (
                          <Tooltip>
                            <TooltipTrigger
                              render={(props) => (
                                <button
                                  {...props}
                                  type="button"
                                  onClick={() =>
                                    canImpersonateThisUser &&
                                    setPendingImpersonateUser(user)
                                  }
                                  disabled={!canImpersonateThisUser}
                                  aria-label={`Act as ${user.name}`}
                                  className={cn(
                                    "flex size-7 items-center justify-center rounded-sm transition-colors",
                                    canImpersonateThisUser
                                      ? "text-primary hover:bg-muted"
                                      : "cursor-not-allowed text-muted-foreground opacity-50"
                                  )}
                                >
                                  <UserCog size={15} />
                                </button>
                              )}
                            />
                            <TooltipContent>
                              {canImpersonateThisUser
                                ? getTooltip("users.actions.impersonate")
                                : getTooltip("users.pending.impersonateDisabled")}
                            </TooltipContent>
                          </Tooltip>
                        )
                      })()}

                    {canDelete &&
                      (() => {
                        const isTenantAdmin =
                          user.platformRoles.includes("TENANT_ADMIN")
                        return (
                          <Tooltip>
                            <TooltipTrigger
                              render={(props) => (
                                <button
                                  {...props}
                                  type="button"
                                  onClick={() =>
                                    !isTenantAdmin &&
                                    setPendingDeleteUser(user)
                                  }
                                  disabled={isTenantAdmin}
                                  aria-label={`Delete ${user.name}`}
                                  className={cn(
                                    "flex size-7 items-center justify-center rounded-sm transition-colors",
                                    isTenantAdmin
                                      ? "cursor-not-allowed text-muted-foreground opacity-50"
                                      : "text-[var(--rust)] hover:bg-[var(--rust-tint)]"
                                  )}
                                >
                                  <Trash2 size={15} />
                                </button>
                              )}
                            />
                            <TooltipContent>
                              {isTenantAdmin
                                ? getTooltip("users.tenantAdmin.deleteDisabled")
                                : getTooltip("users.actions.delete")}
                            </TooltipContent>
                          </Tooltip>
                        )
                      })()}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {pendingUser && (
        <UserStatusDialog
          user={pendingUser}
          open={pendingUser !== null}
          onOpenChange={(open) => {
            if (!open) setPendingUser(null)
          }}
          onConfirm={(reason) => {
            onToggleStatus(pendingUser.id, reason)
            setPendingUser(null)
          }}
        />
      )}

      {pendingResetUser && (
        <UserResetPasswordDialog
          user={pendingResetUser}
          open={pendingResetUser !== null}
          onOpenChange={(open) => {
            if (!open) setPendingResetUser(null)
          }}
          onConfirm={() => {
            onResetPassword(pendingResetUser.id)
            setPendingResetUser(null)
          }}
        />
      )}

      {pendingDeleteUser && (
        <UserDeleteDialog
          user={pendingDeleteUser}
          open={pendingDeleteUser !== null}
          onOpenChange={(open) => {
            if (!open) setPendingDeleteUser(null)
          }}
          onConfirm={() => {
            onDelete(pendingDeleteUser.id)
            setPendingDeleteUser(null)
          }}
        />
      )}

      {pendingImpersonateUser && (
        <UserImpersonateDialog
          user={pendingImpersonateUser}
          open={pendingImpersonateUser !== null}
          onOpenChange={(open) => {
            if (!open) setPendingImpersonateUser(null)
          }}
          onConfirm={() => {
            onImpersonate(pendingImpersonateUser.id)
            setPendingImpersonateUser(null)
          }}
        />
      )}
    </div>
  )
}