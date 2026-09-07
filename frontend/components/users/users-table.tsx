"use client"

import { useState } from "react"
import { Eye, KeyRound, Pencil, UserCheck, UserX } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { SortableTh } from "@/components/ui/sortable-th"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { useSortableTable } from "@/hooks/use-sortable-table"
import { getTooltip } from "@/lib/tooltips"
import { cn } from "@/lib/utils"

import { UserResetPasswordDialog } from "./user-reset-password-dialog"
import { UserStatusDialog } from "./user-status-dialog"
import type { UserRecord } from "./types"

interface UsersTableProps {
  users: UserRecord[]
  onToggleStatus: (id: string) => void
  onResetPassword: (id: string) => void
  onView: (user: UserRecord) => void
  onEdit: (user: UserRecord) => void
}

type SortKey = "name" | "username" | "email" | "status" | "lastLogin"

/**
 * Name / Username / Email / Role(s) / Status / Last login / Actions — per
 * dms-admin-users.html. Status changes always go through the confirmation
 * dialog (U6 / `UserStatusDialog`), and password resets go through U7 /
 * `UserResetPasswordDialog` — there's no instant action here, since both
 * affect a real person's account and shouldn't be one-click accidents.
 * Reset Password is hidden entirely for an inactive user — resetting the
 * password of an account nobody can currently sign into isn't a
 * meaningful action; reactivate first.
 */
export function UsersTable({
  users,
  onToggleStatus,
  onResetPassword,
  onView,
  onEdit,
}: UsersTableProps) {
  const [pendingUser, setPendingUser] = useState<UserRecord | null>(null)
  const [pendingResetUser, setPendingResetUser] = useState<UserRecord | null>(
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
                  {user.email}
                </td>
                <td className="px-3 py-3">
                  <div className="flex flex-wrap gap-1">
                    {user.roles.map((role) => (
                      <span
                        key={role}
                        className="rounded-[6px] border border-[var(--border-strong)] bg-muted px-2 py-0.5 text-[11px] font-semibold whitespace-nowrap text-foreground"
                      >
                        {role}
                      </span>
                    ))}
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

                    {user.status === "Active" && (
                      <Tooltip>
                        <TooltipTrigger
                          render={(props) => (
                      <button
                              {...props}
                        type="button"
                        onClick={() => setPendingResetUser(user)}
                              aria-label={`Reset password for ${user.name}`}
                              className="flex size-7 items-center justify-center rounded-[7px] text-primary transition-colors hover:bg-muted"
                      >
                              <KeyRound size={15} />
                      </button>
                          )}
                        />
                        <TooltipContent>{getTooltip("users.actions.resetPassword")}</TooltipContent>
                      </Tooltip>
                    )}

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
    </div>
  )
}