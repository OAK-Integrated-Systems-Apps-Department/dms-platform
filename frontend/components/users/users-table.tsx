"use client"

import { Eye, Pencil } from "lucide-react"

import { cn } from "@/lib/utils"

import { UserStatusToggle } from "./user-status-toggle"
import type { UserRecord } from "./types"

interface UsersTableProps {
  users: UserRecord[]
  onToggleStatus: (id: string) => void
  onView: (user: UserRecord) => void
  onEdit: (user: UserRecord) => void
}

const HEADERS = ["Name", "Username", "Email", "Role(s)", "Status", "Last login", "Actions"]

/** Name / Username / Email / Role(s) / Status / Last login / Actions — per dms-admin-users.html. */
export function UsersTable({ users, onToggleStatus, onView, onEdit }: UsersTableProps) {
  return (
    <div className="overflow-hidden rounded-[14px] border border-border bg-card shadow-[var(--ledger-shadow)]">
      <div className="overflow-x-auto">
        <table className="w-full min-w-[860px] border-collapse">
          <thead>
            <tr className="border-b border-border">
              {HEADERS.map((header) => (
                <th
                  key={header}
                  className="px-3 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase"
                >
                  {header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {users.length === 0 && (
              <tr>
                <td colSpan={HEADERS.length} className="px-3 py-10 text-center text-[13px] text-muted-foreground">
                  No users match your search or filters.
                </td>
              </tr>
            )}
            {users.map((user) => (
              <tr key={user.id} className="border-b border-border last:border-b-0 hover:bg-muted">
                <td className="px-3 py-3 text-[13.5px] font-semibold text-primary">{user.name}</td>
                <td className="px-3 py-3 font-mono text-[12.5px] text-muted-foreground">{user.username}</td>
                <td className="px-3 py-3 text-[13px] text-muted-foreground">{user.email}</td>
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
                  <UserStatusToggle status={user.status} onToggle={() => onToggleStatus(user.id)} />
                </td>
                <td className="px-3 py-3 font-mono text-[12.5px] text-muted-foreground">{user.lastLogin}</td>
                <td className="px-3 py-3">
                  <div className="flex items-center gap-3.5">
                    <button
                      type="button"
                      onClick={() => onView(user)}
                      className="flex items-center gap-1 text-[12.5px] font-semibold text-primary hover:underline"
                    >
                      <Eye size={13} />
                      View
                    </button>
                    <button
                      type="button"
                      onClick={() => onEdit(user)}
                      className="flex items-center gap-1 text-[12.5px] font-semibold text-primary hover:underline"
                    >
                      <Pencil size={13} />
                      Edit
                    </button>
                    <button
                      type="button"
                      onClick={() => onToggleStatus(user.id)}
                      className={cn(
                        "text-[12.5px] font-semibold hover:underline",
                        user.status === "active" ? "text-[var(--rust)]" : "text-primary"
                      )}
                    >
                      {user.status === "active" ? "Deactivate" : "Activate"}
                    </button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}