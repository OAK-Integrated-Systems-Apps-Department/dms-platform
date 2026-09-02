"use client"

import { useMemo, useState } from "react"
import { useRouter } from "next/navigation"
import { ChevronDown, RotateCcw, Search, ShieldCheck } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible"
import { cn } from "@/lib/utils"

import { permissionGroups } from "./data"
import type { RoleRecord } from "./types"

interface RolePermissionConfigProps {
  role: RoleRecord
  onSave: (permissions: string[]) => void
}

/**
 * Role Permission Configuration (R3) — the dedicated screen for editing a
 * single role's permissions, grouped by DMS functional area. Split out from
 * `RoleForm` (which still edits name/description/status, and inline-edits
 * permissions for a fast path) so this can be reached on its own, per the
 * wireframe's "CONFIGURE PERMISSIONS →" flow from Add/Edit Role.
 *
 * Caveat carried over from the wireframe: Keycloak realm roles are flat
 * name+description records with no native concept of fine-grained
 * permissions. This grouped model assumes a DMS-side table mapping role →
 * permission ids (enforced in DMS's own authorization logic) rather than
 * Keycloak's heavier Authorization Services feature — confirm which
 * approach the backend actually takes before wiring this up to a real API.
 */
export function RolePermissionConfig({
  role,
  onSave,
}: RolePermissionConfigProps) {
  const router = useRouter()
  const isSystemRole = Boolean(role.system)

  const [permissions, setPermissions] = useState<string[]>(role.permissions)
  const [query, setQuery] = useState("")
  // 32 modules is too many to show fully expanded by default, so groups
  // start collapsed; a module with a match while searching auto-opens.
  const [expanded, setExpanded] = useState<Record<string, boolean>>({})

  const isDirty = useMemo(() => {
    if (permissions.length !== role.permissions.length) return true
    const original = new Set(role.permissions)
    return permissions.some((id) => !original.has(id))
  }, [permissions, role.permissions])

  const filteredGroups = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return permissionGroups
    return permissionGroups
      .map((group) => ({
        ...group,
        permissions: group.permissions.filter(
          (permission) =>
            permission.label.toLowerCase().includes(q) ||
            group.module.toLowerCase().includes(q)
        ),
      }))
      .filter((group) => group.permissions.length > 0)
  }, [query])

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
    onSave(permissions)
  }

  return (
    <div className="rounded-[14px] border border-border bg-card px-[30px] py-[26px] shadow-[var(--ledger-shadow)]">
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
          {permissionGroups.reduce((n, g) => n + g.permissions.length, 0)}{" "}
          permissions selected
        </span>
      </div>

      {isSystemRole && (
        <div className="mb-6 rounded-[9px] border border-[var(--gold)] bg-[var(--gold-tint)] px-3.5 py-2.5 text-[12.5px] text-[#7a5b00]">
          This is a system role. Its permissions are managed by the platform and
          can&apos;t be edited here.
        </div>
      )}

      {/* Search */}
      <div className="relative mb-5">
        <Search
          size={16}
          className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2 text-[var(--ink-faint)]"
        />
        <input
          type="text"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="Search permissions…"
          className="h-[38px] w-full rounded-[9px] border border-border bg-muted pr-3 pl-9 text-[13.5px] text-foreground outline-none placeholder:text-[var(--ink-faint)] focus-visible:border-primary focus-visible:bg-card"
        />
      </div>

      {/* Permission groups */}
      <div className="space-y-3">
        {filteredGroups.length === 0 && (
          <p className="rounded-[10px] border border-dashed border-border px-3.5 py-6 text-center text-[13px] text-muted-foreground">
            No permissions match &quot;{query}&quot;.
          </p>
        )}

        {filteredGroups.map((group) => {
          const groupIds = group.permissions.map((p) => p.id)
          const selectedCount = groupIds.filter((id) =>
            permissions.includes(id)
          ).length
          const allSelected =
            selectedCount === groupIds.length && groupIds.length > 0
          const isOpen = query.trim() !== "" || expanded[group.module] === true

          return (
            <Collapsible
              key={group.module}
              open={isOpen}
              onOpenChange={(open) =>
                setExpanded((prev) => ({ ...prev, [group.module]: open }))
              }
              className="overflow-hidden rounded-[10px] border border-border"
            >
              <div className="flex items-center justify-between gap-3 bg-muted px-3.5 py-2">
                <CollapsibleTrigger className="flex flex-1 items-center gap-2 text-left text-[11px] font-semibold tracking-[0.05em] text-muted-foreground uppercase">
                  <ChevronDown
                    size={14}
                    className={cn(
                      "shrink-0 transition-transform",
                      !isOpen && "-rotate-90"
                    )}
                  />
                  {group.module}
                  <span className="font-mono text-[10px] font-normal tracking-normal text-[var(--ink-faint)] normal-case">
                    {group.moduleCode} · {selectedCount}/{groupIds.length}
                  </span>
                </CollapsibleTrigger>
                {!isSystemRole && (
                  <button
                    type="button"
                    onClick={() => setGroupPermissions(groupIds, !allSelected)}
                    className="shrink-0 text-[11.5px] font-semibold text-primary hover:underline"
                  >
                    {allSelected ? "Clear all" : "Select all"}
                  </button>
                )}
              </div>
              <CollapsibleContent className="divide-y divide-border px-3.5">
                {group.permissions.map((permission) => (
                  <Checkbox
                    key={permission.id}
                    id={permission.id}
                    label={permission.label}
                    disabled={isSystemRole}
                    checked={permissions.includes(permission.id)}
                    onCheckedChange={(checked) =>
                      togglePermission(permission.id, checked)
                    }
                  />
                ))}
              </CollapsibleContent>
            </Collapsible>
          )
        })}
      </div>

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
            className="rounded-[9px] border border-[var(--border-strong)] bg-card px-5 py-2.5 text-[13px] font-semibold text-foreground"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={handleSave}
            disabled={isSystemRole || !isDirty}
            className={cn(
              "rounded-[9px] border border-primary bg-primary px-[22px] py-2.5 text-[13px] font-bold tracking-[0.02em] text-primary-foreground",
              "disabled:cursor-not-allowed disabled:opacity-60"
            )}
          >
            Save Permissions
          </button>
        </div>
      </div>
    </div>
  )
}
