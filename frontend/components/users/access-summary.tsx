"use client"

import { useEffect, useMemo, useState } from "react"
import { flushSync } from "react-dom"
import { Check, ChevronDown, Loader2, X } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible"
import { LoadingState } from "@/components/ui/loading-state"
import { SearchInput } from "@/components/ui/search-input"
import { cn } from "@/lib/utils"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { usePermissionCatalog } from "@/components/roles/use-permission-catalog"
import {
  filterPermissionGroups,
  getLeafPermissionIds,
} from "@/components/roles/permission-utils"
import type { PermissionItem } from "@/components/roles/types"
import { useRolesStore } from "@/stores/roles-store"

import { UserStatusDialog } from "./user-status-dialog"
import type { UserRecord } from "./types"

interface UserAccessSummaryProps {
  user: UserRecord
  onToggleStatus: (reason?: string) => void
  /** Whether the signed-in user may activate/deactivate this account — requires `otua.manage-users.activate-deactivate` (see `usePermissions`). */
  canManage: boolean
}

/** A single granted/not-granted status dot — the read-only equivalent of
 * PermissionPicker's interactive `<Checkbox>`. */
function GrantIndicator({
  granted,
  size,
}: {
  granted: boolean
  size: 16 | 18
}) {
  return (
    <span
      className={cn(
        "flex shrink-0 items-center justify-center rounded-full",
        size === 18 ? "size-[18px]" : "size-[16px]",
        granted
          ? "bg-primary text-primary-foreground"
          : "border border-border text-[var(--ink-faint)]"
      )}
    >
      {granted ? (
        <Check size={size === 18 ? 12 : 10} strokeWidth={3} />
      ) : (
        <X size={size === 18 ? 11 : 9} strokeWidth={3} />
      )}
    </span>
  )
}

/**
 * One permission row — a plain leaf, or (for an action like "Manage Users")
 * its own nested `<Collapsible>` of fine-grained permissions, mirroring
 * PermissionPicker's action-level collapse (same chevron size/placement,
 * `(granted/total)` styling) minus the "Select all"/"Clear all" button —
 * this view is read-only, so a `GrantIndicator` dot stands in for
 * PermissionPicker's `<Checkbox>`.
 *
 * Auto-expand-if-granted (and search-forces-open) applies at this level
 * too, same as the module level above it — PermissionPicker itself always
 * starts an action group collapsed regardless of selection, but leaving
 * a granted permission hidden one level inside an already-auto-expanded
 * module would undercut the whole point of auto-expanding it.
 */
function renderPermissionRow(
  permission: PermissionItem,
  effectivePermissions: Set<string>,
  openActions: Record<string, boolean>,
  isSearching: boolean,
  onToggleAction: (actionId: string, open: boolean) => void
) {
  const children = permission.permissions
  if (!children || children.length === 0) {
    const granted = effectivePermissions.has(permission.id)
    return (
      <div
        key={permission.id}
        className="flex items-center justify-between py-2.5"
      >
        <span className="text-[13px] text-foreground">{permission.label}</span>
        <GrantIndicator granted={granted} size={18} />
      </div>
    )
  }

  const childIds = children.map((child) => child.id)
  const grantedCount = childIds.filter((id) =>
    effectivePermissions.has(id)
  ).length
  const isActionOpen =
    isSearching ||
    (openActions[permission.id] ??
      (grantedCount > 0 && openActions[permission.id] !== false))

  return (
    <Collapsible
      key={permission.id}
      open={isActionOpen}
      onOpenChange={(open) => onToggleAction(permission.id, open)}
      className="py-1.5"
    >
      <CollapsibleTrigger className="flex w-full items-start gap-1.5 text-left text-[13px] font-semibold text-foreground">
        <ChevronDown
          size={12}
          className={cn(
            "mt-0.5 shrink-0 text-muted-foreground transition-transform",
            !isActionOpen && "-rotate-90"
          )}
        />
        <span className="min-w-0 flex-1">{permission.label}</span>
        <span className="shrink-0 font-mono text-[10.5px] font-normal text-[var(--ink-faint)]">
          ({grantedCount}/{childIds.length})
        </span>
      </CollapsibleTrigger>
      <CollapsibleContent className="divide-y divide-border border-t border-dashed border-border pl-4">
        {children.map((child) => {
          const granted = effectivePermissions.has(child.id)
          return (
            <div
              key={child.id}
              className="flex items-center justify-between py-2"
            >
              <span className="text-[12.5px] text-foreground">
                {child.label}
              </span>
              <GrantIndicator granted={granted} size={16} />
            </div>
          )
        })}
      </CollapsibleContent>
    </Collapsible>
  )
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
 * `user.roles` (role names) stands in for that role-mappings response
 * until a real `GET /admin/realms/{realm}/users/{id}/role-mappings` call
 * replaces it — this reads the same field the User List and View User
 * screens already display, rather than a separate id-based lookup, so
 * there's one source of truth for "which roles does this user hold."
 *
 * Corner radius is fixed at 10px across every surface here (card, avatar,
 * badges), matching the rest of Role Management and Self-Service.
 */
export function UserAccessSummary({
  user,
  onToggleStatus,
}: UserAccessSummaryProps) {
  const roles = useRolesStore((state) => state.roles)
  const rolesError = useRolesStore((state) => state.error)
  const fetchRoles = useRolesStore((state) => state.fetchRoles)
  const fetchRolePermissions = useRolesStore(
    (state) => state.fetchRolePermissions
  )
  const { hasAnyPermission } = usePermissions()
  const canViewRoles = hasAnyPermission(
    PermissionCodes.ASM_ROLES_VIEW_ROLE,
    PermissionCodes.ASM_ROLES_UPDATE_ROLE
  )
  // Same two codes as `canViewRoles` above, not this hook's own default
  // gate (built for the four permission-*editing* screens) — see the
  // hook's own doc comment for why: this screen's roles/effective-
  // permissions fetch is already gated on this exact pair, so gating the
  // catalog fetch on anything else could let one of the two loading
  // states resolve (and its count render) before the other, or after a
  // caller who can see roles but not the legacy/assign-permissions set.
  const { groups: permissionGroups, loading: catalogLoading } =
    usePermissionCatalog([
      PermissionCodes.ASM_ROLES_VIEW_ROLE,
      PermissionCodes.ASM_ROLES_UPDATE_ROLE,
    ])
  const [statusDialogOpen, setStatusDialogOpen] = useState(false)
  // 32 modules is too many to show fully expanded by default; open only
  // modules where this user actually has a granted permission.
  const [expanded, setExpanded] = useState<Record<string, boolean>>({})
  // Same shape as `expanded` above, one level down — see
  // `renderPermissionRow`'s own doc comment for why this mirrors
  // PermissionPicker's per-action `openActions` rather than staying
  // always-expanded.
  const [openActions, setOpenActions] = useState<Record<string, boolean>>({})
  const [grantsLoading, setGrantsLoading] = useState(true)
  const [query, setQuery] = useState("")
  // Same reasoning as PermissionPicker's own isModuleOpen: while
  // searching, force every module left in the filtered results open
  // (a match would otherwise stay hidden behind its still-collapsed
  // parent) — collapsed-by-default / granted-first only applies to the
  // unfiltered browse state.
  const isSearching = query.trim().length > 0
  // Gates every numeric/rendered count below, not just `grantsLoading` on
  // its own — the catalog (denominator) and the roles/effective-
  // permissions fetch (numerator) are two independent requests, gated on
  // the same permissions but resolving at different times. Waiting on
  // both together is what keeps this screen from ever showing a
  // numerator computed from one and a denominator from the other, e.g.
  // effective permissions already loaded while the catalog is still on
  // its first fetch.
  const isLoading = grantsLoading || catalogLoading

  // GET /api/v1/roles requires asm.manage-roles-permissions.view-role or
  // .update-role — otua.manage-users.* alone (what actually gates
  // reaching this page) doesn't imply it, so skip the call entirely for
  // a caller without either, rather than 403 and, since nothing here
  // marks that permanent otherwise, keep re-firing it on every remount —
  // same guard User List's role filter uses.
  useEffect(() => {
    if (canViewRoles && roles.length === 0 && !rolesError) fetchRoles()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [canViewRoles])

  const rolesHeld = useMemo(
    () => roles.filter((role) => user.roles.includes(role.name)),
    [roles, user.roles]
  )

  // Real granted permissions for each held role, fetched in parallel —
  // same pattern PermissionMatrix uses (see that component's own doc
  // comment) — rather than trusting whatever's already cached on
  // `rolesHeld` from the global roles store. `fetchRoles()` above
  // deliberately never populates `permissions` (see roles-store.ts's
  // `fromBackend` doc comment); without this, a role's `permissions`
  // stays `[]` — and effectivePermissions below computes empty —  unless
  // an admin happened to separately open that specific role's Details/
  // Edit/Permission-Config page earlier in the same session.
  const roleIdsKey = rolesHeld.map((role) => role.id).join(",")
  // Loading flag set for each new set of held roles during render (against
  // the last-seen key, null so the first render counts too); the effect
  // below only does the fetching.
  const [prevRoleIdsKey, setPrevRoleIdsKey] = useState<string | null>(null)
  if (roleIdsKey !== prevRoleIdsKey) {
    setPrevRoleIdsKey(roleIdsKey)
    setGrantsLoading(rolesHeld.length > 0)
  }
  useEffect(() => {
    if (rolesHeld.length === 0) return
    let cancelled = false
    Promise.all(rolesHeld.map((role) => fetchRolePermissions(role.id))).then(
      () => {
        if (!cancelled) setGrantsLoading(false)
      }
    )
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [roleIdsKey])

  const effectivePermissions = useMemo(() => {
    const merged = new Set<string>()
    for (const role of rolesHeld) {
      for (const permissionId of role.permissions) merged.add(permissionId)
    }
    return merged
  }, [rolesHeld])

  // Permission Summary — same shape/sort as Role Details' equivalent
  // block (alphabetical by module name, only modules with at least one
  // grant) — but computed straight off the full, unfiltered catalog
  // rather than `sortedGroups` below, since this block isn't affected by
  // the accordion's own search box.
  const grantedModules = useMemo(() => {
    return permissionGroups
      .map((group) => {
        const grantedCount = getLeafPermissionIds(group.permissions).filter(
          (id) => effectivePermissions.has(id)
        ).length
        return { group, grantedCount }
      })
      .filter((g) => g.grantedCount > 0)
      .sort((a, b) => a.group.module.localeCompare(b.group.module))
  }, [permissionGroups, effectivePermissions])

  // A Permission Summary badge click scrolls the target module's entry in
  // the Effective Permissions accordion into view. flushSync commits the
  // expansion (and the cleared `query`, so the target module can't be
  // hidden by a stale search) to the DOM first, so the entry is already
  // there, open, by the time it's scrolled to.
  function handleSummaryBadgeClick(moduleCode: string) {
    flushSync(() => {
      setQuery("")
      setExpanded((prev) => ({ ...prev, [moduleCode]: true }))
    })
    document
      .getElementById(`access-summary-module-${moduleCode}`)
      ?.scrollIntoView({ behavior: "smooth", block: "start" })
  }

  // Same search as PermissionPicker (shared filterPermissionGroups), run
  // before the granted-first sort below so a search still respects it —
  // e.g. searching "delete" still surfaces a granted "delete" permission
  // ahead of an ungranted one.
  const filteredGroups = useMemo(
    () => filterPermissionGroups(permissionGroups, query),
    [permissionGroups, query]
  )

  // Granted-first (auto-expanded), ungranted-last (collapsed) — the
  // common case is a handful of granted permissions among 32 modules,
  // and catalog order buries them exactly when this screen's whole point
  // is answering "what can this user actually access." A stable
  // partition, not a numeric sort, keeps each half in catalog order.
  const sortedGroups = useMemo(() => {
    const withGrants = filteredGroups.map((group) => {
      const groupIds = getLeafPermissionIds(group.permissions)
      const grantedCount = groupIds.filter((id) =>
        effectivePermissions.has(id)
      ).length
      return { group, groupIds, grantedCount }
    })
    const granted = withGrants.filter((g) => g.grantedCount > 0)
    const ungranted = withGrants.filter((g) => g.grantedCount === 0)
    return [...granted, ...ungranted]
  }, [filteredGroups, effectivePermissions])

  function isModuleOpen(moduleCode: string, grantedCount: number) {
    if (isSearching) return true
    return (
      expanded[moduleCode] ??
      (grantedCount > 0 && expanded[moduleCode] !== false)
    )
  }

  const initials =
    user.name
      .trim()
      .split(/\s+/)
      .map((part) => part[0])
      .join("")
      .toUpperCase() || "?"

  return (
    <div className="rounded-md border border-border bg-card px-[30px] py-[26px] shadow-[var(--ledger-shadow)]">
      {/* Identity — name, status, and role badges inline on one wrapping
          row (role badges used to live in their own "Roles Held" section
          below; merging them here removes that section's header/margin
          overhead entirely, not just tightens it, which mattered most
          for a user with only one or two roles). The platform-role note
          below still explains the gold/outline badge distinction. */}
      <div className="mb-6 flex items-center gap-4">
        <span className="flex size-14 shrink-0 items-center justify-center rounded-md bg-primary font-heading text-[18px] font-bold text-primary-foreground">
          {initials}
        </span>
        <div>
          <div className="mb-0.5 flex flex-wrap items-center gap-2">
            <p className="font-heading text-[17px] font-semibold">
              {user.name}
            </p>
            <Badge
              variant={user.status === "Active" ? "default" : "muted"}
              className="gap-1.5 rounded-md"
            >
              <span className="size-[5px] rounded-full bg-current" />
              {user.status}
            </Badge>
            {user.platformRoles.map((role) => (
              <Badge key={role} variant="gold" className="rounded-md">
                {role}
              </Badge>
            ))}
            {rolesHeld.map((role) => (
              <Badge key={role.id} variant="outline" className="rounded-md">
                {role.name}
              </Badge>
            ))}
            {rolesHeld.length === 0 && user.platformRoles.length === 0 && (
              <span className="text-[13px] text-muted-foreground">
                No roles assigned
              </span>
            )}
          </div>
          <p className="font-mono text-[12px] text-muted-foreground">
            {user.username} · Tenant: {user.tenant}
          </p>
        </div>
      </div>
      {user.platformRoles.length > 0 && (
        <p className="mb-6 text-[12px] text-muted-foreground">
          {user.platformRoles.join(", ")}{" "}
          {user.platformRoles.length === 1 ? "is a" : "are"} platform role
          {user.platformRoles.length === 1 ? "" : "s"}, not a tenant-defined DMS
          role — the effective-permissions breakdown below reflects only
          tenant-defined roles, since platform roles grant full access outright
          rather than a specific permission set.
        </p>
      )}

      <hr className="my-6 border-dashed border-border" />

      {/* Permission summary — only modules this user actually has a
          grant in; the full module-by-module breakdown (including
          untouched modules) lives in Effective Permissions below. Same
          shape/sort as Role Details' equivalent block, but each badge
          scrolls to and expands that module's entry further down this
          same page instead of navigating away — Access Summary merges
          permissions across every role this user holds, so there's no
          single role's Role Permission Configuration page to link to. */}
      <h3 className="mb-2.5 flex items-center justify-between text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
        <span>Permission Summary</span>
        {!isLoading && (
          <span className="font-mono text-[10.5px] font-normal tracking-normal text-[var(--ink-faint)] normal-case">
            {grantedModules.length} of {permissionGroups.length} modules
          </span>
        )}
      </h3>
      {isLoading ? (
        <div className="mb-6 flex items-center gap-2 text-[13px] text-muted-foreground">
          <Loader2 size={14} className="animate-spin" />
          Loading permission catalog…
        </div>
      ) : (
        <div className="mb-6 flex flex-wrap gap-2">
          {grantedModules.map(({ group, grantedCount }) => (
            <button
              key={group.moduleCode}
              type="button"
              onClick={() => handleSummaryBadgeClick(group.moduleCode)}
            >
              <Badge
                variant="outline"
                className="rounded-md py-1 transition-colors hover:border-primary hover:text-primary"
              >
                {group.moduleCode} — {group.module} — {grantedCount}{" "}
                {grantedCount === 1 ? "permission" : "permissions"}
              </Badge>
            </button>
          ))}
          {grantedModules.length === 0 && (
            <p className="text-[13px] text-muted-foreground">
              No permissions granted yet.
            </p>
          )}
        </div>
      )}

      <hr className="my-6 border-dashed border-border" />

      {/* Effective permissions */}
      <div className="mb-2.5 flex items-center justify-between">
        <h3 className="text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
          Effective Permissions (merged across roles)
        </h3>
        <span className="text-[12px] text-muted-foreground">
          {isLoading
            ? "Loading…"
            : `${effectivePermissions.size} of ${permissionGroups.reduce(
                (n, g) => n + getLeafPermissionIds(g.permissions).length,
                0
              )}`}
        </span>
      </div>

      {isLoading ? (
        <LoadingState message="Loading effective permissions…" />
      ) : (
        <>
          <div className="mb-5 flex flex-wrap items-center gap-3">
            <SearchInput
              className="min-w-[220px] flex-1"
              value={query}
              onChange={setQuery}
              placeholder="Search permissions…"
            />
          </div>

          {sortedGroups.length === 0 ? (
            <p className="rounded-md border border-dashed border-border px-3.5 py-6 text-center text-[13px] text-muted-foreground">
              No permissions match &quot;{query}&quot;.
            </p>
          ) : (
            <div className="grid grid-cols-1 gap-3.5 sm:grid-cols-2 lg:grid-cols-3">
              {sortedGroups.map(({ group, groupIds, grantedCount }) => {
                const isOpen = isModuleOpen(group.moduleCode, grantedCount)

                return (
                  <Collapsible
                    key={group.moduleCode}
                    id={`access-summary-module-${group.moduleCode}`}
                    open={isOpen}
                    onOpenChange={(open) =>
                      setExpanded((prev) => ({
                        ...prev,
                        [group.moduleCode]: open,
                      }))
                    }
                    className={cn(
                      "overflow-hidden rounded-md border border-border",
                      isOpen && "sm:col-span-2 lg:col-span-3"
                    )}
                  >
                    <CollapsibleTrigger className="flex w-full items-start gap-2 bg-muted px-3.5 py-2 text-left text-[11px] font-semibold tracking-[0.05em] text-muted-foreground uppercase">
                      <ChevronDown
                        size={14}
                        className={cn(
                          "mt-0.5 shrink-0 transition-transform",
                          !isOpen && "-rotate-90"
                        )}
                      />
                      <span className="min-w-0 flex-1">{group.module}</span>
                      <span className="shrink-0 font-mono text-[10px] font-normal tracking-normal text-[var(--ink-faint)] normal-case">
                        {group.moduleCode} · {grantedCount}/{groupIds.length}
                      </span>
                    </CollapsibleTrigger>
                    <CollapsibleContent className="divide-y divide-border px-3.5">
                      {group.permissions.map((permission) =>
                        renderPermissionRow(
                          permission,
                          effectivePermissions,
                          openActions,
                          isSearching,
                          (actionId, open) =>
                            setOpenActions((prev) => ({
                              ...prev,
                              [actionId]: open,
                            }))
                        )
                      )}
                    </CollapsibleContent>
                  </Collapsible>
                )
              })}
            </div>
          )}
        </>
      )}

      <p className="mt-4 text-[12px] text-muted-foreground">
        Read-only, computed view — resolves overlapping or conflicting
        permissions across all assigned roles into a single effective set.
      </p>

      <UserStatusDialog
        user={user}
        open={statusDialogOpen}
        onOpenChange={setStatusDialogOpen}
        onConfirm={(reason) => onToggleStatus(reason)}
      />
    </div>
  )
}
