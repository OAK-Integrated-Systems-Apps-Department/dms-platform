"use client"

import { useMemo, useState } from "react"
import { useRouter } from "next/navigation"
import { AlertTriangle, RotateCcw, ShieldCheck } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { cn } from "@/lib/utils"

import { getLeafPermissionIds } from "./permission-utils"
import { PermissionPicker } from "./permission-picker"
import type { RoleRecord } from "./types"
import { usePermissionCatalog } from "./use-permission-catalog"

interface RolePermissionConfigProps {
  role: RoleRecord
  /** Resolves with an error message on failure (shown inline, panel stays open), or null on success. */
  onSave: (permissions: string[]) => Promise<string | null>
}

/**
 * Role Permission Configuration (R3) — the dedicated screen for editing a
 * single role's permissions, grouped by DMS functional area. Split out from
 * `RoleForm` (which still edits name/description/status, and inline-edits
 * permissions for a fast path) so this can be reached on its own, per the
 * wireframe's "CONFIGURE PERMISSIONS →" flow from Add/Edit Role.
 *
 * The actual picker (search, expand/collapse, module/action checkbox tree)
 * is `PermissionPicker`, shared with `RoleForm` so the two screens don't
 * maintain divergent implementations of the same UI — this component only
 * owns what's specific to it: the role identity strip, the system-role/
 * error banners, and the Reset/Cancel/Save actions.
 *
 * Reads the live catalog via `usePermissionCatalog` (backed by
 * `V10__seed_fine_grained_permissions.sql`), and `onSave` now actually
 * reaches the database — see `useRolesStore`'s `syncRolePermissions`,
 * which the parent page wires this up to.
 */
export function RolePermissionConfig({
  role,
  onSave,
}: RolePermissionConfigProps) {
  const router = useRouter()
  const isSystemRole = Boolean(role.system)
  const { groups: permissionGroups, loading: catalogLoading } = usePermissionCatalog()

  const [permissions, setPermissions] = useState<string[]>(role.permissions)
  const [saving, setSaving] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)

  const isDirty = useMemo(() => {
    if (permissions.length !== role.permissions.length) return true
    const original = new Set(role.permissions)
    return permissions.some((id) => !original.has(id))
  }, [permissions, role.permissions])

  function togglePermission(id: string, checked: boolean) {
    if (isSystemRole) return
    setPermissions((prev) =>
      checked ? [...prev, id] : prev.filter((p) => p !== id)
    )
  }

  function setGroupPermissions(groupIds: string[], checked: boolean) {
    if (isSystemRole) return
    setPermissions((prev) => {
      const withoutGroup = prev.filter((id) => !groupIds.includes(id))
      return checked ? [...withoutGroup, ...groupIds] : withoutGroup
    })
  }

  function handleReset() {
    setPermissions(role.permissions)
  }

  function handleCancel() {
    router.push(`/admin/roles/${role.id}/edit`)
  }

  function handleSave() {
    if (isSystemRole) return
    setSubmitError(null)
    setSaving(true)
    onSave(permissions).then((error) => {
      setSaving(false)
      if (error) setSubmitError(error)
    })
  }

  return (
    <div className="rounded-lg border border-border bg-card px-[30px] py-[26px] shadow-[var(--ledger-shadow)]">
      {/* Role identity strip */}
      <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-2">
          <Badge variant="outline">Role: {role.name}</Badge>
          {isSystemRole && (
            <Badge variant="gold">
              <ShieldCheck size={12} />
              System role — read only
            </Badge>
          )}
        </div>
        <span className="text-[12px] text-muted-foreground">
          {permissions.length} of{" "}
          {permissionGroups.reduce(
            (n, g) => n + getLeafPermissionIds(g.permissions).length,
            0
          )}{" "}
          permissions selected
        </span>
      </div>

      {isSystemRole && (
        <div className="mb-6 rounded-btn border border-[var(--gold)] bg-[var(--gold-tint)] px-3.5 py-2.5 text-[12.5px] text-[var(--gold-foreground)]">
          This is a system role. Its permissions are managed by the platform and
          can&apos;t be edited here.
        </div>
      )}

      {submitError && (
        <div className="mb-6 flex items-start gap-2 rounded-btn border border-[var(--rust)] bg-[var(--rust-tint)] px-3.5 py-2.5 text-[12.5px] text-[var(--rust)]">
          <AlertTriangle size={14} className="mt-0.5 shrink-0" />
          <span>{submitError}</span>
        </div>
      )}

      <PermissionPicker
        groups={permissionGroups}
        loading={catalogLoading}
        selected={permissions}
        onToggle={togglePermission}
        onSetGroup={setGroupPermissions}
        disabled={isSystemRole}
      />

      <hr className="my-6 border-dashed border-border" />

      {/* Actions */}
      <div className="flex items-center justify-between gap-2.5">
        <button
          type="button"
          onClick={handleReset}
          disabled={!isDirty || isSystemRole}
          className="flex items-center gap-1.5 text-[12.5px] font-semibold text-muted-foreground disabled:cursor-not-allowed disabled:opacity-50"
        >
          <RotateCcw size={13} />
          Reset changes
        </button>
        <div className="flex items-center gap-2.5">
          <button
            type="button"
            onClick={handleCancel}
            className="rounded-btn border border-[var(--border-strong)] bg-card px-5 py-2.5 text-[13px] font-semibold text-foreground"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={handleSave}
            disabled={isSystemRole || !isDirty || saving}
            className={cn(
              "rounded-btn border border-primary bg-primary px-[22px] py-2.5 text-[13px] font-bold tracking-[0.02em] text-primary-foreground",
              "disabled:cursor-not-allowed disabled:opacity-60"
            )}
          >
            {saving ? "Saving…" : "Save Permissions"}
          </button>
        </div>
      </div>
    </div>
  )
}
