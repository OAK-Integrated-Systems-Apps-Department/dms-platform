"use client"

import { useMemo, useState } from "react"
import { ChevronDown, Loader2 } from "lucide-react"

import { Checkbox } from "@/components/ui/checkbox"
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible"
import { SearchInput } from "@/components/ui/search-input"
import { cn } from "@/lib/utils"

import { countSelectedLeafPermissions, getLeafPermissionIds } from "./permission-utils"
import type { PermissionGroup, PermissionItem } from "./types"

interface PermissionPickerProps {
  groups: PermissionGroup[]
  loading: boolean
  /** Ids of the permissions currently granted. */
  selected: string[]
  onToggle: (id: string, checked: boolean) => void
  onSetGroup: (ids: string[], checked: boolean) => void
  /** Read-only mode (e.g. a system role) — hides every Select all/Clear control and disables the checkboxes. */
  disabled?: boolean
}

/**
 * Shared permission-selection UI: search, per-module Expand all/Collapse
 * all, and the module -> action -> permission checkbox tree, each level
 * with its own "Select all"/"Clear" toggle. Used by both Role Permission
 * Configuration (`RolePermissionConfig`) and the Add/Edit Role quick
 * picker (`RoleForm`) so the two screens don't maintain two divergent
 * implementations of the same picker - `RoleForm` used to have a
 * simplified version with no search, no expand/collapse, and no
 * per-module select-all at all.
 *
 * Search auto-expands every module/action left in the filtered results
 * (matches would otherwise stay hidden behind their still-collapsed
 * parent) - collapsed-by-default only applies to the unfiltered browse
 * state, via `isModuleOpen`/`isActionOpen` below rather than the raw
 * `openModules`/`openActions` state directly.
 */
export function PermissionPicker({
  groups,
  loading,
  selected,
  onToggle,
  onSetGroup,
  disabled = false,
}: PermissionPickerProps) {
  const [query, setQuery] = useState("")
  const [openModules, setOpenModules] = useState<Record<string, boolean>>({})
  const [openActions, setOpenActions] = useState<Record<string, boolean>>({})

  const isSearching = query.trim().length > 0

  const filteredGroups = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return groups
    return groups
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
  }, [query, groups])

  function isModuleOpen(moduleCode: string) {
    return isSearching || openModules[moduleCode] === true
  }
  function isActionOpen(actionId: string) {
    return isSearching || openActions[actionId] === true
  }

  return (
    <div>
      <div className="mb-5 flex flex-wrap items-center gap-3">
        <SearchInput
          className="min-w-[220px] flex-1"
          value={query}
          onChange={setQuery}
          placeholder="Search permissions…"
        />
        <div className="flex items-center gap-1 rounded-lg border border-border p-0.5">
          <button
            type="button"
            onClick={() =>
              setOpenModules(
                Object.fromEntries(groups.map((g) => [g.moduleCode, true]))
              )
            }
            className="rounded-md px-2.5 py-1.5 text-[12px] font-medium text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
          >
            Expand all
          </button>
          <div className="h-4 w-px bg-border" />
          <button
            type="button"
            onClick={() => {
              setOpenModules({})
              setOpenActions({})
            }}
            className="rounded-md px-2.5 py-1.5 text-[12px] font-medium text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
          >
            Collapse all
          </button>
        </div>
      </div>

      <div className="grid grid-cols-1 gap-3.5 sm:grid-cols-2 lg:grid-cols-3">
        {loading ? (
          <div className="col-span-full flex items-center gap-2 rounded-[10px] border border-dashed border-border px-3.5 py-6 text-[13px] text-muted-foreground">
            <Loader2 size={15} className="animate-spin" />
            Loading the permission catalog…
          </div>
        ) : (
          <>
            {filteredGroups.length === 0 && (
              <p className="col-span-full rounded-[10px] border border-dashed border-border px-3.5 py-6 text-center text-[13px] text-muted-foreground">
                No permissions match &quot;{query}&quot;.
              </p>
            )}

            {filteredGroups.map((group) => {
              const groupIds = getLeafPermissionIds(group.permissions)
              const selectedCount = countSelectedLeafPermissions(
                group.permissions,
                selected
              )
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
                  className={cn(
                    "overflow-hidden rounded-[10px] border border-border",
                    isOpen && "sm:col-span-2 lg:col-span-3"
                  )}
                >
                  <div className="flex items-center justify-between gap-3 bg-muted px-3.5 py-2">
                    <CollapsibleTrigger className="flex flex-1 items-start gap-2 text-left text-[11px] font-semibold tracking-[0.05em] text-muted-foreground uppercase">
                      <ChevronDown
                        size={14}
                        className={cn(
                          "mt-0.5 shrink-0 transition-transform",
                          !isOpen && "-rotate-90"
                        )}
                      />
                      <span className="min-w-0 flex-1">{group.module}</span>
                      <span className="shrink-0 font-mono text-[10.5px] font-normal tracking-normal text-[var(--ink-faint)] normal-case">
                        {group.moduleCode} · {selectedCount}/{groupIds.length}
                      </span>
                    </CollapsibleTrigger>
                    {!disabled && (
                      <button
                        type="button"
                        onClick={() => onSetGroup(groupIds, !allSelected)}
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
                            disabled={disabled}
                            checked={selected.includes(permission.id)}
                            onCheckedChange={(checked) =>
                              onToggle(permission.id, checked)
                            }
                          />
                        )
                      }

                      // Action with fine-grained permissions nested under it
                      // (e.g. "Manage Users") — its own id isn't grantable,
                      // so show it as a collapsible sub-heading with its own
                      // select-all for its children instead of a checkbox
                      // of its own. Collapsed by default, same as the
                      // module it lives in.
                      const childIds = children.map((child) => child.id)
                      const childSelectedCount = countSelectedLeafPermissions(
                        children,
                        selected
                      )
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
                            <CollapsibleTrigger className="flex flex-1 items-start gap-1.5 text-left text-[13px] font-semibold text-foreground">
                              <ChevronDown
                                size={12}
                                className={cn(
                                  "mt-0.5 shrink-0 text-muted-foreground transition-transform",
                                  !isActionOpenNow && "-rotate-90"
                                )}
                              />
                              <span className="min-w-0 flex-1">
                                {permission.label}
                              </span>
                              <span className="shrink-0 font-mono text-[10.5px] font-normal text-[var(--ink-faint)]">
                                ({childSelectedCount}/{childIds.length})
                              </span>
                            </CollapsibleTrigger>
                            {!disabled && (
                              <button
                                type="button"
                                onClick={() =>
                                  onSetGroup(childIds, !allChildrenSelected)
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
                                disabled={disabled}
                                checked={selected.includes(child.id)}
                                onCheckedChange={(checked) =>
                                  onToggle(child.id, checked)
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
          </>
        )}
      </div>
    </div>
  )
}
