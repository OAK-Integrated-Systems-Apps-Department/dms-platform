"use client"

import { useState } from "react"
import { Eye, KeyRound, Pencil, Trash2, UserCheck, UserX } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { SortableTh } from "@/components/ui/sortable-th"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { useSortableTable } from "@/hooks/use-sortable-table"
import { getTooltip } from "@/lib/tooltips"
import { cn } from "@/lib/utils"

import { UserDeleteDialog } from "./user-delete-dialog"
import { UserResetPasswordDialog } from "./user-reset-password-dialog"
import { UserStatusDialog } from "./user-status-dialog"
import type { UserRecord } from "./types"

interface UsersTableProps {
  users: UserRecord[]
  onToggleStatus: (id: string) => void
  onResetPassword: (id: string) => void
  onDelete: (id: string) => void
  onView: (user: UserRecord) => void
  onEdit: (user: UserRecord) => void

  /**
   * Whether the signed-in user may delete other accounts at all — only
   * the tenant admin can (see `lib/auth/use-is-tenant-admin.ts`). The
   * Delete column is left out entirely for everyone else, rather than
   * shown disabled, since it isn't a capability they can ever use.
   */
  canDelete: boolean
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
  canDelete,
}: UsersTableProps) {
  const [pendingUser, setPendingUser] = useState<UserRecord | null>(null)
  const [pendingResetUser, setPendingResetUser] = useState<UserRecord | null>(
    null
  )
  const [pendingDeleteUser, setPendingDeleteUser] = useState<UserRecord | null>(
    null
  )

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
    <div className="overflow-hidden rounded-[14px] border border-border bg-card shadow-[var(--ledger-shadow)]">
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
                  className="px-3 py-10 text-center text-[13px] text-muted-foreground"
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
                      <span
                        key={role}
                        title="Platform role — assigned directly in Keycloak, not a tenant-defined DMS role"
                        className="rounded-[6px] border border-transparent bg-[var(--gold-tint)] px-2 py-0.5 text-[11px] font-semibold whitespace-nowrap text-[#7a5b00]"
                      >
                        {role}
                      </span>
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
                    className="gap-1.5 rounded-[10px]"
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
                            className="flex size-7 items-center justify-center rounded-[7px] text-primary transition-colors hover:bg-muted"
                          >
                            <Eye size={15} />
                          </button>
                        )}
                      />
                      <TooltipContent>{getTooltip("users.actions.view")}</TooltipContent>
                    </Tooltip>

                    <Tooltip>
                      <TooltipTrigger
                        render={(props) => (
                          <button
                            {...props}
                            type="button"
                            onClick={() => onEdit(user)}
                            aria-label={`Edit ${user.name}`}
                            className="flex size-7 items-center justify-center rounded-[7px] text-primary transition-colors hover:bg-muted"
                          >
                            <Pencil size={15} />
                          </button>
                        )}
                      />
                      <TooltipContent>{getTooltip("users.actions.edit")}</TooltipContent>
                    </Tooltip>

                    {(() => {
                      const canResetPassword = user.status === "Active"
                      return (
                        <Tooltip>
                          <TooltipTrigger
                            render={(props) => (
                              <button
                                {...props}
                                type="button"
                                onClick={() =>
                                  canResetPassword && setPendingResetUser(user)
                                }
                                disabled={!canResetPassword}
                                aria-label={`Reset password for ${user.name}`}
                                className={cn(
                                  "flex size-7 items-center justify-center rounded-[7px] transition-colors",
                                  canResetPassword
                                    ? "text-primary hover:bg-muted"
                                    : "cursor-not-allowed text-muted-foreground opacity-50"
                                )}
                              >
                                <KeyRound size={15} />
                              </button>
                            )}
                          />
                          <TooltipContent>
                            {canResetPassword
                              ? getTooltip("users.actions.resetPassword")
                              : getTooltip("users.pending.resetPasswordDisabled")}
                          </TooltipContent>
                        </Tooltip>
                      )
                    })()}

                    <Tooltip>
                      <TooltipTrigger
                        render={(props) => (
                          <button
                            {...props}
                            type="button"
                            onClick={() => setPendingUser(user)}
                            aria-label={
                              user.status === "Active"
                                ? `Deactivate ${user.name}`
                                : `Activate ${user.name}`
                            }
                            className={cn(
                              "flex size-7 items-center justify-center rounded-[7px] transition-colors",
                              user.status === "Active"
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
                        {user.status === "Active"
                          ? getTooltip("users.actions.deactivate")
                          : getTooltip("users.actions.activate")}
                      </TooltipContent>
                    </Tooltip>

                    {canDelete &&
                      (() => {
                        const isTenantAdmin =
                          user.platformRoles.includes("TENANT_ADMIN")
                        // Delete hits the same soft-delete endpoint as
                        // Deactivate (see UserController#deactivateUser /
                        // lib/users/api.ts#deleteUser) and that endpoint
                        // 404s on a user that's already deactivated. The
                        // User List deliberately still shows deactivated
                        // users (with an Inactive badge) rather than
                        // hiding them, so without this check a
                        // previously-deleted row would keep a clickable
                        // Delete button that always fails.
                        const isAlreadyInactive = user.status !== "Active"
                        const deleteDisabled = isTenantAdmin || isAlreadyInactive
                        return (
                          <Tooltip>
                            <TooltipTrigger
                              render={(props) => (
                                <button
                                  {...props}
                                  type="button"
                                  onClick={() =>
                                    !deleteDisabled &&
                                    setPendingDeleteUser(user)
                                  }
                                  disabled={deleteDisabled}
                                  aria-label={`Delete ${user.name}`}
                                  className={cn(
                                    "flex size-7 items-center justify-center rounded-[7px] transition-colors",
                                    deleteDisabled
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
                                : isAlreadyInactive
                                  ? getTooltip("users.inactive.deleteDisabled")
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
          onConfirm={() => {
            onToggleStatus(pendingUser.id)
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
    </div>
  )
}