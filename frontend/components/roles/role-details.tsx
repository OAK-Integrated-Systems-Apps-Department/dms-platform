"use client"

import { useMemo, useState } from "react"
import Link from "next/link"
import { useRouter } from "next/navigation"
import { AlertTriangle, Loader2, Pencil, ShieldCheck, Trash2 } from "lucide-react"
import { toast } from "sonner"

import { Badge } from "@/components/ui/badge"
import { SortableTh } from "@/components/ui/sortable-th"
import { useSortableTable } from "@/hooks/use-sortable-table"
import { useRolesStore } from "@/stores/roles-store"
import { useUsers } from "@/lib/users/use-users"

import { getLeafPermissionIds } from "./permission-utils"
import { RoleDeleteDialog } from "./role-delete-dialog"
import type { RoleRecord } from "./types"
import { usePermissionCatalog } from "./use-permission-catalog"

interface RoleDetailsProps {
  role: RoleRecord
  /** Whether the signed-in user may edit this role — requires `asm.manage-roles-permissions.update-role` (see `usePermissions`). Someone with only `.view-role` can reach this read-only page at all, but gets none of the three buttons below. */
  canEdit: boolean
  /** Whether the signed-in user may delete this role — requires `asm.manage-roles-permissions.delete-role`. */
  canDelete: boolean
  /** Whether the signed-in user may reach Role Permission Configuration for this role — requires `asm.manage-roles-permissions.assign-permissions` (that page grants/revokes permissions, a separate action from editing the role's own name/status). */
  canManagePermissions: boolean
}

type AssignedUserSortKey = "name" | "username" | "email" | "status"

/**
 * Role Details / Assigned Users (R4) — read-only view of a role's
 * definition, its permissions by functional area, and the users currently
 * holding it. Permissions are edited via Role Permission Configuration, and
 * the role's name/description/status via Edit Role — this screen is
 * reference-only, per the wireframe. Delete Role is the one exception:
 * it's destructive rather than an edit, so it lives here as its own
 * confirm-and-act flow rather than folding into Edit Role.
 *
 * FIXED KNOWN GAP: Assigned Users used to read `stores/users-store.ts`, a
 * pure in-memory mock store seeded from `components/users/data.ts` and
 * never connected to the backend — so this table always showed the same
 * hardcoded mock users regardless of who was actually assigned. It now
 * reads `useUsers()` (`lib/users/use-users.ts`), the same real,
 * backend-backed data source the User List and View User screens use,
 * matching on `user.roles.includes(role.name)` exactly as before — that
 * part of the logic wasn't wrong, only what it was reading from was.
 * There's no dedicated "users by role" backend endpoint yet, so this
 * pays the same cost `useUsers()` already pays elsewhere (fetching every
 * user in the tenant plus each one's role list); fine at tenant-roster
 * scale, would need a real `GET /api/v1/roles/{roleId}/users` endpoint to
 * scale further.
 */
export function RoleDetails({ role, canEdit, canDelete, canManagePermissions }: RoleDetailsProps) {
  const router = useRouter()
  const deleteRole = useRolesStore((state) => state.deleteRole)
  const [showDeleteDialog, setShowDeleteDialog] = useState(false)

  const { users, isPending: usersLoading, isError: usersError } = useUsers()
  const assignedUsers = useMemo(
    () => users.filter((user) => user.roles.includes(role.name)),
    [users, role.name]
  )

  const {
    sorted: sortedAssignedUsers,
    sortKey: assignedUsersSortKey,
    direction: assignedUsersDirection,
    toggleSort: toggleAssignedUsersSort,
  } = useSortableTable<(typeof assignedUsers)[number], AssignedUserSortKey>(
    assignedUsers,
    {
      name: (u) => u.name,
      username: (u) => u.username,
      email: (u) => u.email,
      status: (u) => u.status,
    }
  )

  const { groups: permissionGroups, loading: catalogLoading } = usePermissionCatalog()

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
        <Badge
          variant={role.status === "Active" ? "default" : "muted"}
          className="gap-1.5 rounded-[10px]"
        >
          <span className="size-[5px] rounded-full bg-current" />
          {role.status}
        </Badge>
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
          the catalog returns modules in. */}
      <h3 className="mb-2.5 flex items-center justify-between text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
        <span>Permission Summary</span>
        {!catalogLoading && (
          <span className="font-mono text-[10.5px] font-normal tracking-normal text-[var(--ink-faint)] normal-case">
            {permissionSummary.filter((g) => g.count > 0).length} of{" "}
            {permissionSummary.length} modules
          </span>
        )}
      </h3>
      {catalogLoading ? (
        <div className="mb-6 flex items-center gap-2 text-[13px] text-muted-foreground">
          <Loader2 size={14} className="animate-spin" />
          Loading permission catalog…
        </div>
      ) : (
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
      )}
      <p className="mb-6 text-[12px] text-muted-foreground">
        {role.permissions.length} total permissions granted.
        {canManagePermissions && (
          <>
            {" "}
            <Link
              href={`/admin/roles/${role.id}/permissions`}
              className="font-semibold text-primary hover:underline"
            >
              View full configuration →
            </Link>
          </>
        )}
      </p>

      <hr className="my-6 border-dashed border-border" />

      {/* Assigned users */}
      <div className="mb-2.5 flex items-center justify-between">
        <h3 className="text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
          Assigned Users
        </h3>
        {!usersLoading && !usersError && (
          <span className="text-[12px] text-muted-foreground">
            {assignedUsers.length} user{assignedUsers.length === 1 ? "" : "s"}
          </span>
        )}
      </div>

      {usersLoading ? (
        <div className="flex items-center gap-2 rounded-[10px] border border-border py-8 text-[13px] text-muted-foreground">
          <Loader2 size={14} className="mx-auto animate-spin" />
          <span className="mx-auto">Loading assigned users…</span>
        </div>
      ) : usersError ? (
        <div className="flex items-center justify-center gap-2 rounded-[10px] border border-[var(--rust)] bg-[var(--rust-tint)] py-6 text-[13px] text-[var(--rust)]">
          <AlertTriangle size={14} />
          Could not load assigned users.
        </div>
      ) : (
        <div className="overflow-hidden rounded-[10px] border border-border">
          <table className="w-full border-collapse">
            <thead>
              <tr className="border-b border-border bg-muted">
                <SortableTh
                  label="Name"
                  active={assignedUsersSortKey === "name"}
                  direction={assignedUsersDirection}
                  onClick={() => toggleAssignedUsersSort("name")}
                />
                <SortableTh
                  label="Username"
                  active={assignedUsersSortKey === "username"}
                  direction={assignedUsersDirection}
                  onClick={() => toggleAssignedUsersSort("username")}
                />
                <SortableTh
                  label="Email"
                  active={assignedUsersSortKey === "email"}
                  direction={assignedUsersDirection}
                  onClick={() => toggleAssignedUsersSort("email")}
                />
                <SortableTh
                  label="Status"
                  active={assignedUsersSortKey === "status"}
                  direction={assignedUsersDirection}
                  onClick={() => toggleAssignedUsersSort("status")}
                />
              </tr>
            </thead>
            <tbody>
              {assignedUsers.length === 0 && (
                <tr>
                  <td
                    colSpan={4}
                    className="px-3.5 py-8 text-center text-[13px] text-muted-foreground"
                  >
                    No users are currently assigned this role.
                  </td>
                </tr>
              )}
              {sortedAssignedUsers.map((user) => (
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
                  <td className="px-3.5 py-3 text-[13px] text-muted-foreground">
                    {user.email}
                  </td>
                  <td className="px-3.5 py-3">
                    <Badge
                      variant={user.status === "Active" ? "default" : "muted"}
                      className="gap-1.5 rounded-[10px]"
                    >
                      <span className="size-[5px] rounded-full bg-current" />
                      {user.status}
                    </Badge>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <hr className="my-6 border-dashed border-border" />

      {/* Actions */}
      <div className="flex items-center justify-end gap-2.5">
        {role.system ? (
          <span className="text-[12px] text-muted-foreground">
            System roles can&apos;t be edited or deleted.
          </span>
        ) : canEdit || canDelete ? (
          <>
            {canDelete && (
            <button
              type="button"
              onClick={() => setShowDeleteDialog(true)}
              className="flex items-center gap-1.5 rounded-[10px] border border-[var(--rust)] px-5 py-2.5 text-[13px] font-bold tracking-[0.02em] text-[var(--rust)]"
            >
              <Trash2 size={14} />
              Delete Role
            </button>
            )}
            {canEdit && (
            <Link
              href={`/admin/roles/${role.id}/edit`}
              className="flex items-center gap-1.5 rounded-[10px] border border-primary bg-primary px-5 py-2.5 text-[13px] font-bold tracking-[0.02em] text-primary-foreground"
            >
              <Pencil size={14} />
              Edit Role
            </Link>
            )}
          </>
        ) : null}
      </div>

      {canDelete && (
        <RoleDeleteDialog
          role={role}
          open={showDeleteDialog}
          onOpenChange={setShowDeleteDialog}
          onConfirm={async () => {
            const { error } = await deleteRole(role.id)
            if (!error) {
              toast.success(`${role.name} deleted.`)
              router.push("/admin/roles")
            } else {
              toast.error(error)
            }
            return error
          }}
        />
      )}
    </div>
  )
}
