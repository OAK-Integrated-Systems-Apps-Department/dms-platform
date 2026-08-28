"use client"

import { useMemo, useState, Fragment } from "react"
import { Check } from "lucide-react"

import { permissionGroups } from "./data"
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

  const visibleGroups = useMemo(
    () =>
      area === ALL_AREAS
        ? permissionGroups
        : permissionGroups.filter((group) => group.area === area),
    [area]
  )

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
            <option key={group.area} value={group.area}>
              {group.area}
            </option>
          ))}
        </select>
        <span className="text-[12px] text-muted-foreground">
          {roles.length} roles ×{" "}
          {visibleGroups.reduce((n, g) => n + g.permissions.length, 0)}{" "}
          permissions
        </span>
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
            {visibleGroups.map((group) => (
              <Fragment key={group.area}>
                {area === ALL_AREAS && (
                  <tr className="bg-muted/60">
                    <td
                      colSpan={roles.length + 1}
                      className="px-3.5 py-1.5 text-[10.5px] font-semibold tracking-[0.06em] text-muted-foreground uppercase"
                    >
                      {group.area}
                    </td>
                  </tr>
                )}
                {group.permissions.map((permission) => (
                  <tr
                    key={permission.id}
                    className="border-b border-border last:border-b-0 hover:bg-muted/50"
                  >
                    <td className="sticky left-0 bg-card px-3.5 py-2.5 text-[13px] text-foreground">
                      {permission.label}
                    </td>
                    {roles.map((role) => {
                      const granted = role.permissions.includes(permission.id)
                      return (
                        <td key={role.id} className="px-3 py-2.5 text-center">
                          <span
                            className={
                              granted
                                ? "mx-auto flex size-[18px] items-center justify-center rounded-full bg-primary text-primary-foreground"
                                : "mx-auto block size-[18px] rounded-full border border-border"
                            }
                          >
                            {granted && <Check size={12} strokeWidth={3} />}
                          </span>
                        </td>
                      )
                    })}
                  </tr>
                ))}
              </Fragment>
            ))}
          </tbody>
        </table>
      </div>

      <p className="mt-3.5 text-[12px] text-muted-foreground">
        Read-only reference view — permissions are edited via Role Permission
        Configuration, not from this matrix.
      </p>
    </div>
  )
}
