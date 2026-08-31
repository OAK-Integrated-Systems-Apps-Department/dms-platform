"use client"

import { useMemo, useState } from "react"
import { Check, X } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { assignedUsersByRole, permissionGroups } from "@/components/roles/data"
import { useRolesStore } from "@/stores/roles-store"
import { useUsersStore } from "@/stores/users-store"

import { UserStatusDialog } from "./user-status-dialog"
import type { UserRecord } from "./types"

interface UserAccessSummaryProps {
  user: UserRecord
}

/**
 * User Access Summary (A1) — consolidated, read-only view of what a user
 * can actually do: which roles they hold, and the effective permission set
 * merged across those roles. Answers "what can this user access" without
 * checking each role individually.
 *
 * Necessarily a DMS-side computation: Keycloak's role-mappings endpoint
 * returns which roles a user holds, but the effective-permission merge
 * across those roles is computed here, not returned by Keycloak directly
 * (same caveat as Permission Matrix and Role Permission Configuration).
 * `assignedUsersByRole` stands in for that role-mappings response until a
 * real `GET /admin/realms/{realm}/users/{id}/role-mappings` call replaces
 * it.
 *
 * Corner radius is fixed at 10px across every surface here (card, avatar,
 * badges), matching the rest of Role Management and Self-Service.
 */
export function UserAccessSummary({ user }: UserAccessSummaryProps) {
  const roles = useRolesStore((state) => state.roles)
  const toggleUserStatus = useUsersStore((state) => state.toggleStatus)
  const [statusDialogOpen, setStatusDialogOpen] = useState(false)

  const rolesHeld = useMemo(
    () =>
      roles.filter((role) =>
        (assignedUsersByRole[role.id] ?? []).some((u) => u.id === user.id)
      ),
    [roles, user.id]
  )

  const effectivePermissions = useMemo(() => {
    const merged = new Set<string>()
    for (const role of rolesHeld) {
      for (const permissionId of role.permissions) merged.add(permissionId)
    }
    return merged
  }, [rolesHeld])

  const initials =
    user.name
      .trim()
      .split(/\s+/)
      .map((part) => part[0])
      .join("")
      .toUpperCase() || "?"

  return (
    <div className="rounded-[10px] border border-border bg-card px-[30px] py-[26px] shadow-[var(--ledger-shadow)]">
      {/* Identity */}
      <div className="mb-6 flex items-center justify-between gap-3">
        <div className="flex items-center gap-4">
          <span className="flex size-14 shrink-0 items-center justify-center rounded-[10px] bg-primary font-heading text-[18px] font-bold text-primary-foreground">
            {initials}
          </span>
          <div>
            <div className="mb-0.5 flex items-center gap-2">
              <p className="font-heading text-[17px] font-semibold">
                {user.name}
              </p>
              <Badge
                variant={user.status === "Active" ? "default" : "muted"}
                className="gap-1.5 rounded-[10px]"
              >
                <span className="size-[5px] rounded-full bg-current" />
                {user.status}
              </Badge>
            </div>
            <p className="font-mono text-[12px] text-muted-foreground">
              {user.username} · Tenant: {user.tenant}
            </p>
          </div>
        </div>
        <button
          type="button"
          onClick={() => setStatusDialogOpen(true)}
          className={
            user.status === "Active"
              ? "shrink-0 rounded-[10px] border border-[var(--rust)] bg-[var(--rust-tint)] px-4 py-2.5 text-[13px] font-semibold text-[var(--rust)]"
              : "shrink-0 rounded-[10px] border border-primary bg-primary/10 px-4 py-2.5 text-[13px] font-semibold text-primary"
          }
        >
          {user.status === "Active" ? "Deactivate User" : "Activate User"}
        </button>
      </div>

      {/* Roles held */}
      <h3 className="mb-2.5 text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
        Roles Held
      </h3>
      <div className="mb-6 flex flex-wrap gap-1.5">
        {rolesHeld.length === 0 && (
          <p className="text-[13px] text-muted-foreground">
            This user isn&apos;t assigned any roles.
          </p>
        )}
        {rolesHeld.map((role) => (
          <Badge key={role.id} variant="outline" className="rounded-[10px]">
            {role.name}
          </Badge>
        ))}
      </div>

      <hr className="my-6 border-dashed border-border" />

      {/* Effective permissions */}
      <div className="mb-2.5 flex items-center justify-between">
        <h3 className="text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
          Effective Permissions (merged across roles)
        </h3>
        <span className="text-[12px] text-muted-foreground">
          {effectivePermissions.size} of{" "}
          {permissionGroups.reduce((n, g) => n + g.permissions.length, 0)}
        </span>
      </div>

      <div className="space-y-3">
        {permissionGroups.map((group) => (
          <div
            key={group.area}
            className="overflow-hidden rounded-[10px] border border-border"
          >
            <div className="bg-muted px-3.5 py-2 text-[11px] font-semibold tracking-[0.05em] text-muted-foreground uppercase">
              {group.area}
            </div>
            <div className="divide-y divide-border">
              {group.permissions.map((permission) => {
                const granted = effectivePermissions.has(permission.id)
                return (
                  <div
                    key={permission.id}
                    className="flex items-center justify-between px-3.5 py-2.5"
                  >
                    <span className="text-[13px] text-foreground">
                      {permission.label}
                    </span>
                    <span
                      className={
                        granted
                          ? "flex size-[18px] items-center justify-center rounded-full bg-primary text-primary-foreground"
                          : "flex size-[18px] items-center justify-center rounded-full border border-border text-[var(--ink-faint)]"
                      }
                    >
                      {granted ? (
                        <Check size={12} strokeWidth={3} />
                      ) : (
                        <X size={11} strokeWidth={3} />
                      )}
                    </span>
                  </div>
                )
              })}
            </div>
          </div>
        ))}
      </div>

      <p className="mt-4 text-[12px] text-muted-foreground">
        Read-only, computed view — resolves overlapping or conflicting
        permissions across all assigned roles into a single effective set.
      </p>

      <UserStatusDialog
        user={user}
        open={statusDialogOpen}
        onOpenChange={setStatusDialogOpen}
        onConfirm={() => toggleUserStatus(user.id)}
      />
    </div>
  )
}
