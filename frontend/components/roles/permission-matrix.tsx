"use client"

import { useMemo, useState, Fragment } from "react"
import { Check, ChevronRight } from "lucide-react"

import { cn } from "@/lib/utils"
import { permissionGroups } from "./data"
import { getLeafPermissionIds } from "./permission-utils"
import { useRolesStore } from "@/stores/roles-store"

const ALL_AREAS = "All functional areas"

/**
 * Permission Matrix (R5) — read-only Roles × Permissions grid so admins can
 * see at a glance which role grants which DMS action, per the wireframe.
 * Editing still happens on Role Permission Configuration, not here.
 *
 * This view is necessarily a DMS-side composition: Keycloak has no single
 * endpoint that returns a roles-by-permissions grid, so in a real
 * integration this comes from `GET /admin/realms/{realm}/roles` plus each
 * role's stored permission mapping (see the same caveat on Role Permission
 * Configuration and Role Details).
 *
 * Corner radius is fixed at 10px across every surface here (card, table
 * wrapper, filter control), matching Role Details rather than the app-wide
 * 9px/14px mix used elsewhere in Role Management.
 */
export function PermissionMatrix() {
  const roles = useRolesStore((state) => state.roles)
  const [area, setArea] = useState(ALL_AREAS)
  // Rows are grouped two levels deep (module, then action-with-children) and
  // both start collapsed — 32 modules x ~92 actions is far too much to lay
  // out flat by default. Keyed by moduleCode / action id.
  const [openModules, setOpenModules] = useState<Record<string, boolean>>({})
  const [openActions, setOpenActions] = useState<Record<string, boolean>>({})

  const visibleGroups = useMemo(
    () =>
      area === ALL_AREAS
        ? permissionGroups
        : permissionGroups.filter((group) => group.module === area),
    [area]
  )

  function toggleModule(moduleCode: string) {
    setOpenModules((prev) => ({ ...prev, [moduleCode]: !prev[moduleCode] }))
  }
  function toggleAction(actionId: string) {
    setOpenActions((prev) => ({ ...prev, [actionId]: !prev[actionId] }))
  }

  return (
    <div className="rounded-[10px] border border-border bg-card px-[30px] py-[26px] shadow-[var(--ledger-shadow)]">
      {/* Toolbar */}
      <div className="mb-5 flex items-center justify-between gap-3">
        <select
          value={area}
          onChange={(event) => setArea(event.target.value)}
          className="h-[38px] rounded-[10px] border border-border bg-muted px-3 text-[13px] font-medium text-foreground outline-none focus-visible:border-primary focus-visible:bg-card"
        >
          <option value={ALL_AREAS}>{ALL_AREAS}</option>
          {permissionGroups.map((group) => (
            <option key={group.moduleCode} value={group.module}>
              {group.moduleCode} — {group.module}
            </option>
          ))}
        </select>
        <div className="flex items-center gap-3">
          <button
            type="button"
            onClick={() => {
              setOpenModules(
                Object.fromEntries(
                  visibleGroups.map((g) => [g.moduleCode, true])
                )
              )
            }}
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
          <span className="text-[12px] text-muted-foreground">
            {roles.length} roles ×{" "}
            {visibleGroups.reduce(
              (n, g) => n + getLeafPermissionIds(g.permissions).length,
              0
            )}{" "}
            permissions
          </span>
        </div>
      </div>

      {/* Matrix */}
      <div className="overflow-x-auto rounded-[10px] border border-border">
        <table className="w-full min-w-[560px] border-collapse">
          <thead>
            <tr className="border-b border-border bg-muted">
              <th className="sticky left-0 bg-muted px-3.5 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
                Permission / Function
              </th>
              {roles.map((role) => (
                <th
                  key={role.id}
                  className="px-3 py-2.5 text-center text-[11px] font-semibold tracking-[0.04em] text-muted-foreground uppercase"
                >
                  <span
                    className={role.status === "Inactive" ? "opacity-50" : ""}
                  >
                    {role.name}
                  </span>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {visibleGroups.map((group) => {
              const groupIds = getLeafPermissionIds(group.permissions)
              const moduleOpen =
                area !== ALL_AREAS || openModules[group.moduleCode] === true

              return (
                <Fragment key={group.moduleCode}>
                  {area === ALL_AREAS && (
                    <tr className="bg-muted/60">
                      <td
                        colSpan={roles.length + 1}
                        className="sticky left-0 bg-muted/60 px-3.5 py-1.5"
                      >
                        <button
                          type="button"
                          onClick={() => toggleModule(group.moduleCode)}
                          className="flex w-full items-center gap-1.5 text-left text-[10.5px] font-semibold tracking-[0.06em] text-muted-foreground uppercase"
                        >
                          <ChevronRight
                            size={12}
                            className={cn(
                              "shrink-0 transition-transform",
                              moduleOpen && "rotate-90"
                            )}
                          />
                          {group.moduleCode} — {group.module}
                          <span className="font-mono text-[10px] font-normal tracking-normal text-[var(--ink-faint)] normal-case">
                            ({groupIds.length} permissions)
                          </span>
                        </button>
                      </td>
                    </tr>
                  )}
                  {moduleOpen &&
                    group.permissions.map((permission) => {
                      const hasChildren =
                        permission.permissions &&
                        permission.permissions.length > 0

                      // Action with fine-grained permissions nested under it
                      // (e.g. "Manage Users") — render as a collapsible
                      // sub-heading row, collapsed by default, followed by
                      // one row per grantable child permission when open.
                      if (hasChildren) {
                        const actionOpen = openActions[permission.id] === true
                        return (
                          <Fragment key={permission.id}>
                            <tr className="border-b border-border bg-muted/40">
                              <td
                                colSpan={roles.length + 1}
                                className="sticky left-0 bg-muted/40 px-3.5 py-1.5"
                              >
                                <button
                                  type="button"
                                  onClick={() => toggleAction(permission.id)}
                                  className="flex w-full items-center gap-1.5 text-left text-[12px] font-semibold text-foreground"
                                >
                                  <ChevronRight
                                    size={12}
                                    className={cn(
                                      "shrink-0 text-muted-foreground transition-transform",
                                      actionOpen && "rotate-90"
                                    )}
                                  />
                                  {permission.label}
                                  <span className="font-mono text-[10.5px] font-normal text-[var(--ink-faint)]">
                                    ({permission.permissions!.length})
                                  </span>
                                </button>
                              </td>
                            </tr>
                            {actionOpen &&
                              permission.permissions!.map((child) => (
                                <tr
                                  key={child.id}
                                  className="border-b border-border last:border-b-0 hover:bg-muted/50"
                                >
                                  <td className="sticky left-0 bg-card py-2.5 pr-3.5 pl-7 text-[13px] text-foreground">
                                    {child.label}
                                  </td>
                                  {roles.map((role) => {
                                    const granted = role.permissions.includes(
                                      child.id
                                    )
                                    return (
                                      <td
                                        key={role.id}
                                        className="px-3 py-2.5 text-center"
                                      >
                                        <span
                                          className={
                                            granted
                                              ? "mx-auto flex size-[18px] items-center justify-center rounded-full bg-primary text-primary-foreground"
                                              : "mx-auto block size-[18px] rounded-full border border-border"
                                          }
                                        >
                                          {granted && (
                                            <Check size={12} strokeWidth={3} />
                                          )}
                                        </span>
                                      </td>
                                    )
                                  })}
                                </tr>
                              ))}
                          </Fragment>
                        )
                      }

                      return (
                        <tr
                          key={permission.id}
                          className="border-b border-border last:border-b-0 hover:bg-muted/50"
                        >
                          <td className="sticky left-0 bg-card px-3.5 py-2.5 text-[13px] text-foreground">
                            {permission.label}
                          </td>
                          {roles.map((role) => {
                            const granted = role.permissions.includes(
                              permission.id
                            )
                            return (
                              <td
                                key={role.id}
                                className="px-3 py-2.5 text-center"
                              >
                                <span
                                  className={
                                    granted
                                      ? "mx-auto flex size-[18px] items-center justify-center rounded-full bg-primary text-primary-foreground"
                                      : "mx-auto block size-[18px] rounded-full border border-border"
                                  }
                                >
                                  {granted && (
                                    <Check size={12} strokeWidth={3} />
                                  )}
                                </span>
                              </td>
                            )
                          })}
                        </tr>
                      )
                    })}
                </Fragment>
              )
            })}
          </tbody>
        </table>
      </div>

      <p className="mt-3.5 text-[12px] text-muted-foreground">
        Read-only reference view — permissions are edited via Role Permission
        Configuration, not from this matrix. Module and action rows start
        collapsed; click a row, or use Expand all, to see individual
        permissions.
      </p>
    </div>
  )
}
