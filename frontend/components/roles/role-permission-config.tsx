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
import { getLeafPermissionIds } from "./permission-utils"
import type { PermissionItem, RoleRecord } from "./types"

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
  // Collapsible at both the module level and the action level (e.g. "Manage
  // Users" nested under OTUA). Both start collapsed on load — 32 modules x
  // ~92 actions is too much to show expanded by default — and open either
  // on user click or automatically while a search query is narrowing things
  // down, via `isModuleOpen`/`isActionOpen` below rather than this state
  // directly.
  const [openModules, setOpenModules] = useState<Record<string, boolean>>({})
  const [openActions, setOpenActions] = useState<Record<string, boolean>>({})

  const isDirty = useMemo(() => {
    if (permissions.length !== role.permissions.length) return true
    const original = new Set(role.permissions)
    return permissions.some((id) => !original.has(id))
  }, [permissions, role.permissions])

  const isSearching = query.trim().length > 0

  const filteredGroups = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return permissionGroups
    return permissionGroups
      .map((group) => {
        const moduleMatches = group.module.toLowerCase().includes(q)
        const permissions = group.permissions
          .map((permission): PermissionItem | null => {
            if (permission.permissions && permission.permissions.length > 0) {
              // Parent action: keep every child if the action/module itself
              // matched, otherwise keep only the matching children.
              const children =
                moduleMatches || permission.label.toLowerCase().includes(q)
                  ? permission.permissions
                  : permission.permissions.filter((child) =>
                      child.label.toLowerCase().includes(q)
                    )
              return children.length > 0
                ? { ...permission, permissions: children }
                : null
            }
            return moduleMatches || permission.label.toLowerCase().includes(q)
              ? permission
              : null
          })
          .filter(
            (permission): permission is PermissionItem => permission !== null
          )
        return { ...group, permissions }
      })
      .filter((group) => group.permissions.length > 0)
  }, [query])

  // While searching, auto-expand every module/action left in the filtered
  // results so matches are actually visible — collapsed-by-default only
  // applies to the unfiltered, browse state.
  function isModuleOpen(moduleCode: string) {
    return isSearching || openModules[moduleCode] === true
  }
  function isActionOpen(actionId: string) {
    return isSearching || openActions[actionId] === true
  }

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
          {permissionGroups.reduce(
            (n, g) => n + getLeafPermissionIds(g.permissions).length,
            0
          )}{" "}
          permissions selected
        </span>
      </div>

      <div className="mb-5 flex items-center gap-3">
        <button
          type="button"
          onClick={() =>
            setOpenModules(
              Object.fromEntries(
                permissionGroups.map((g) => [g.moduleCode, true])
              )
            )
          }
          className="text-[11.5px] font-semibold text-primary hover:underline"
        >
          Expand all
        </button>
        <button
          type="button"
          onClick={() => {
            setOpenModules({})
            setOpenActions({})
          }}
          className="text-[11.5px] font-semibold text-primary hover:underline"
        >
          Collapse all
        </button>
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
          const groupIds = getLeafPermissionIds(group.permissions)
          const selectedCount = groupIds.filter((id) =>
            permissions.includes(id)
          ).length
          const allSelected =
            selectedCount === groupIds.length && groupIds.length > 0
          const isOpen = isModuleOpen(group.moduleCode)

          return (
            <Collapsible
              key={group.moduleCode}
              open={isOpen}
              onOpenChange={(open) =>
                setOpenModules((prev) => ({
                  ...prev,
                  [group.moduleCode]: open,
                }))
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
                  <span className="font-mono text-[10.5px] font-normal tracking-normal text-[var(--ink-faint)] normal-case">
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
                {group.permissions.map((permission) => {
                  const children = permission.permissions
                  if (!children || children.length === 0) {
                    return (
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
                    )
                  }

                  // Action with fine-grained permissions nested under it
                  // (e.g. "Manage Users") — its own id isn't grantable, so
                  // show it as a collapsible sub-heading with a select-all
                  // for its children instead of a checkbox of its own.
                  // Collapsed by default, same as the module it lives in.
                  const childIds = children.map((child) => child.id)
                  const childSelectedCount = childIds.filter((id) =>
                    permissions.includes(id)
                  ).length
                  const allChildrenSelected =
                    childSelectedCount === childIds.length &&
                    childIds.length > 0
                  const isActionOpenNow = isActionOpen(permission.id)

                  return (
                    <Collapsible
                      key={permission.id}
                      open={isActionOpenNow}
                      onOpenChange={(open) =>
                        setOpenActions((prev) => ({
                          ...prev,
                          [permission.id]: open,
                        }))
                      }
                      className="py-1.5"
                    >
                      <div className="flex items-center justify-between gap-2 py-1.5">
                        <CollapsibleTrigger className="flex flex-1 items-center gap-1.5 text-left text-[13px] font-semibold text-foreground">
                          <ChevronDown
                            size={12}
                            className={cn(
                              "shrink-0 text-muted-foreground transition-transform",
                              !isActionOpenNow && "-rotate-90"
                            )}
                          />
                          {permission.label}
                          <span className="font-mono text-[10.5px] font-normal text-[var(--ink-faint)]">
                            ({childSelectedCount}/{childIds.length})
                          </span>
                        </CollapsibleTrigger>
                        {!isSystemRole && (
                          <button
                            type="button"
                            onClick={() =>
                              setGroupPermissions(
                                childIds,
                                !allChildrenSelected
                              )
                            }
                            className="shrink-0 text-[11px] font-semibold text-primary hover:underline"
                          >
                            {allChildrenSelected ? "Clear all" : "Select all"}
                          </button>
                        )}
                      </div>
                      <CollapsibleContent className="divide-y divide-border border-t border-dashed border-border pl-4">
                        {children.map((child) => (
                          <Checkbox
                            key={child.id}
                            id={child.id}
                            label={child.label}
                            disabled={isSystemRole}
                            checked={permissions.includes(child.id)}
                            onCheckedChange={(checked) =>
                              togglePermission(child.id, checked)
                            }
                          />
                        ))}
                      </CollapsibleContent>
                    </Collapsible>
                  )
                })}
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
