"use client"

import { useMemo } from "react"
import Link from "next/link"
import { Pencil, ShieldCheck } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { useUsersStore } from "@/stores/users-store"

import { permissionGroups } from "./data"
import { getLeafPermissionIds } from "./permission-utils"
import { RoleStatusBadge } from "./role-status-badge"
import type { RoleRecord } from "./types"

interface RoleDetailsProps {
  role: RoleRecord
}

/**
 * Role Details / Assigned Users (R4) — read-only view of a role's
 * definition, its permissions by functional area, and the users currently
 * holding it. Permissions are edited via Role Permission Configuration, and
 * the role's name/description/status via Edit Role — this screen is
 * reference-only, per the wireframe.
 *
 * Assigned Users is computed live from `useUsersStore`, matching on
 * `user.roles.includes(role.name)` — the same field User Management's own
 * User List and View User screens read — rather than a separate mock
 * keyed by role id. That separate mock used to drift from the real user
 * data (same id, different mock person) now that User Management has its
 * own richer dataset; reading the one canonical source removes that seam.
 *
 * The store is selected as the raw `users` array, with the role-name
 * filter done in a separate `useMemo` — filtering *inside* the zustand
 * selector would return a brand-new array every render, which breaks
 * React's `useSyncExternalStore` ("getServerSnapshot should be cached")
 * with an infinite render loop.
 *
 * Corner radius is fixed at 10px across every surface on this screen
 * (card, table, badges, buttons) rather than following the app-wide
 * 9px/14px mix used elsewhere in Role Management.
 */
export function RoleDetails({ role }: RoleDetailsProps) {
  const users = useUsersStore((state) => state.users)
  const assignedUsers = useMemo(
    () => users.filter((user) => user.roles.includes(role.name)),
    [users, role.name]
  )

  const permissionSummary = permissionGroups
    .map((group) => {
      const count = getLeafPermissionIds(group.permissions).filter((id) =>
        role.permissions.includes(id)
      ).length
      return { module: group.module, moduleCode: group.moduleCode, count }
    })
    .sort((a, b) => a.module.localeCompare(b.module))

  return (
    <div className="rounded-[10px] border border-border bg-card px-[30px] py-[26px] shadow-[var(--ledger-shadow)]">
      {/* Identity */}
      <div className="mb-1 flex flex-wrap items-center gap-2.5">
        <h2 className="font-heading text-[20px] font-semibold">{role.name}</h2>
        <RoleStatusBadge status={role.status} />
        {role.system && (
          <Badge variant="gold" className="rounded-[10px]">
            <ShieldCheck size={12} />
            System role
          </Badge>
        )}
      </div>
      <p className="mb-6 text-[13.5px] leading-[1.6] text-muted-foreground">
        {role.description || "No description provided."}
      </p>

      {/* Permission summary — only modules this role touches; the full
          module-by-module breakdown (including untouched modules) lives on
          Role Permission Configuration. Sorted alphabetically by module
          name so the badge list is easy to scan regardless of the order
          modules are defined in `data.ts`. */}
      <h3 className="mb-2.5 flex items-center justify-between text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
        <span>Permission Summary</span>
        <span className="font-mono text-[10.5px] font-normal tracking-normal text-[var(--ink-faint)] normal-case">
          {permissionSummary.filter((g) => g.count > 0).length} of{" "}
          {permissionSummary.length} modules
        </span>
      </h3>
      <div className="mb-6 flex flex-wrap gap-2">
        {permissionSummary
          .filter((group) => group.count > 0)
          .map((group) => (
            <Badge
              key={group.module}
              variant="outline"
              className="rounded-[10px] py-1"
            >
              {group.moduleCode} — {group.module} — {group.count}{" "}
              {group.count === 1 ? "permission" : "permissions"}
            </Badge>
          ))}
        {permissionSummary.every((group) => group.count === 0) && (
          <p className="text-[13px] text-muted-foreground">
            No permissions granted yet.
          </p>
        )}
      </div>
      <p className="mb-6 text-[12px] text-muted-foreground">
        {role.permissions.length} total permissions granted.{" "}
        <Link
          href={`/admin/roles/${role.id}/permissions`}
          className="font-semibold text-primary hover:underline"
        >
          View full configuration →
        </Link>
      </p>

      <hr className="my-6 border-dashed border-border" />

      {/* Assigned users */}
      <div className="mb-2.5 flex items-center justify-between">
        <h3 className="text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
          Assigned Users
        </h3>
        <span className="text-[12px] text-muted-foreground">
          {assignedUsers.length} user{assignedUsers.length === 1 ? "" : "s"}
        </span>
      </div>

      <div className="overflow-hidden rounded-[10px] border border-border">
        <table className="w-full border-collapse">
          <thead>
            <tr className="border-b border-border bg-muted">
              <th className="px-3.5 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
                Name
              </th>
              <th className="px-3.5 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
                Username
              </th>
              <th className="px-3.5 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
                Status
              </th>
            </tr>
          </thead>
          <tbody>
            {assignedUsers.length === 0 && (
              <tr>
                <td
                  colSpan={3}
                  className="px-3.5 py-8 text-center text-[13px] text-muted-foreground"
                >
                  No users are currently assigned this role.
                </td>
              </tr>
            )}
            {assignedUsers.map((user) => (
              <tr
                key={user.id}
                className="border-b border-border last:border-b-0 hover:bg-muted"
              >
                <td className="px-3.5 py-3 text-[13.5px] font-medium text-foreground">
                  <Link
                    href={`/admin/users/${user.id}`}
                    className="text-primary hover:underline"
                  >
                    {user.name}
                  </Link>
                </td>
                <td className="px-3.5 py-3 font-mono text-[12.5px] text-muted-foreground">
                  {user.username}
                </td>
                <td className="px-3.5 py-3">
                  <RoleStatusBadge status={user.status} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <hr className="my-6 border-dashed border-border" />

      {/* Actions */}
      <div className="flex items-center justify-end">
        {role.system ? (
          <span className="text-[12px] text-muted-foreground">
            System roles can&apos;t be edited.
          </span>
        ) : (
          <Link
            href={`/admin/roles/${role.id}/edit`}
            className="flex items-center gap-1.5 rounded-[10px] border border-primary bg-primary px-5 py-2.5 text-[13px] font-bold tracking-[0.02em] text-primary-foreground"
          >
            <Pencil size={14} />
            Edit Role
          </Link>
        )}
      </div>
    </div>
  )
}
