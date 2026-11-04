"use client"

import { useEffect, useMemo, useRef, useState, Fragment } from "react"
import {
  Check,
  ChevronDown,
  ChevronRight,
  Loader2,
  SlidersHorizontal,
} from "lucide-react"

import { cn } from "@/lib/utils"
import { getLeafPermissionIds } from "./permission-utils"
import { useRolesStore } from "@/stores/roles-store"
import { usePermissionCatalog } from "./use-permission-catalog"

const ALL_AREAS = "All functional areas"

// Shared width for the sticky "Permission / Function" column. Every sticky
// td/th in the table (module rows, action rows, leaf rows, and the header)
// must stay in sync on this value — see the comment block below for why a
// single fixed box matters here.
const COL1_WIDTH = 340

/**
 * Permission Matrix (R5) — read-only Roles × Permissions grid so admins can
 * see at a glance which role grants which DMS action, per the wireframe.
 * Editing still happens on Role Permission Configuration, not here.
 *
 * Reads the live catalog via `usePermissionCatalog` (backed by
 * `V10__seed_fine_grained_permissions.sql`, not the hardcoded
 * `components/roles/data.ts` this used to read from) and each role's real
 * granted permissions via `fetchRolePermissions`, fetched in parallel for
 * every role once the role list loads — see the effect right after
 * `visibleRoles` below, and note it deliberately fetches for every role,
 * not just the ones the Roles filter currently shows, so toggling a
 * hidden role back on shows real data immediately rather than kicking
 * off a fresh fetch. This is the one screen where that N-role fan-out is
 * actually justified: a matrix inherently needs every role's full grant
 * set at once, unlike the Role List table (which never fetches
 * permissions per role; see `stores/roles-store.ts`).
 *
 * Corner radius is fixed at 10px across every surface here (card, table
 * wrapper, filter control), matching Role Details rather than the app-wide
 * 9px/14px mix used elsewhere in Role Management.
 *
 * Row indent is a deliberate 3-level scale (pl-3.5 / pl-7 / pl-10) so depth
 * is readable at a glance: module header < action-group header (and any
 * leaf permission sitting directly under a module) < a leaf permission
 * nested under an action group. Keep each new row type on this scale
 * instead of reusing px-3.5 — that's what previously made every chevron
 * line up at the same left edge regardless of depth.
 *
 * The table wrapper only scrolls horizontally (overflow-x-auto) — there is
 * no inner vertical scroll container. Vertical scrolling is handled by the
 * page itself, so long/expanded matrices scroll the whole page rather than
 * fighting the user with a second, nested scrollbar. Only column 1 stays
 * sticky (`sticky left-0`, plus `z-10`/`z-20` so it paints above columns
 * sliding underneath it during horizontal scroll) so permission labels
 * stay visible while scrolling through many role columns; the header row
 * is not sticky vertically, since there's no scroll container left for
 * `top-0` to stick within.
 *
 * Role header cells are forced single-line (`whitespace-nowrap`) at a
 * 150px min-width rather than allowed to wrap onto two lines. Since the
 * matrix already scrolls horizontally, there's no upside to letting names
 * like "Security Administrator" wrap and eat vertical row height — the
 * column just grows and the user scrolls further right instead. Each
 * header's `title` attribute carries the full role name as a native
 * tooltip fallback.
 *
 * Column 1 (Permission / Function) is a strict, fixed COL1_WIDTH (340px)
 * on EVERY row type, including module and action-group header rows. This
 * is the key structural decision here: module/action rows used to render
 * as a single `<td colSpan={colSpanAll}>` containing the entire row's
 * content (label, badge, everything), which — once any module was expanded
 * and colSpan grew to span all 15+ role columns — made "keep the
 * label+badge pinned to a fixed-width region via sticky + flex/max-width"
 * unreliable: the badge would end up stranded at the cell's real right
 * edge (i.e. the far right of the whole table) or clipped oddly during
 * horizontal scroll, since a colSpan cell doesn't give sticky/flex a
 * dependable fixed-width box to pin against.
 *
 * Fixed now by splitting each header row into two real `<td>`s: a
 * genuinely fixed-width sticky cell for the chevron + label + count badge
 * (identical width/sticky behavior to every leaf permission row's first
 * column), followed by a second, plain `<td colSpan={visibleRoles.length}>`
 * that only supplies the row's background color across the remaining
 * columns and holds no interactive content. Sticky positioning is now
 * unambiguous because it's always operating on a real single-column-width
 * box, exactly like the rest of the table.
 *
 * Because column 1 is now always a genuine fixed box for header rows too,
 * long module/action labels (e.g. "VIRUS AND MALWARE SCANNING BEFORE
 * DOCUMENT INGESTION") wrap onto a second line (`whitespace-normal
 * break-words`) instead of being ellipsis-truncated.
 *
 * Leaf permission label cells (the actual grantable permissions, one row
 * each) previously used `truncate` + `title` to preserve a strict
 * single-line row height. That clipped longer permission labels with an
 * ellipsis even though the widened column had room to show them. They now
 * use the same `break-words whitespace-normal` treatment as the header
 * rows, so the full label is always visible — rows simply grow taller when
 * a label needs two lines. `title` is kept on regardless, as a harmless
 * native-tooltip fallback. Data cells (the checkmark columns) don't need
 * any special handling for this: default `td` vertical-align is `middle`,
 * so they just center against whatever height the row ends up being.
 *
 * Two things specifically address what happens once there are many roles
 * (15+, per Org/Tenant/User Administration seeding real tenants with a lot
 * of custom roles):
 *
 * 1. Role columns get a real min width (150px, via `min-w-[150px]` on each
 *    role `<th>`/`<td>`) and the table's own min-width scales with the
 *    number of *visible* role columns, instead of a flat `min-w-[560px]`.
 *    Before this, a wide role set squeezed every column down until header
 *    names like "Backup &
 *    Recovery Administrator" wrapped onto 2–3 lines — the table should
 *    overflow and let the wrapper's horizontal scrollbar handle it, not
 *    shrink columns illegibly.
 * 2. A checkbox-based "Roles" filter (`hiddenRoleIds`) lets an admin hide
 *    roles they don't care about right now, which is usually a better fix
 *    for a wide tenant than scrolling — same idea as the functional-area
 *    filter, just for columns instead of rows.
 *
 * Toolbar is split into two zones: filters (functional area, roles, and a
 * "Clear filters" link that only appears once something's actually
 * filtered) on the left, and view state (roles/permissions summary +
 * Expand/Collapse segmented control) on the right. The Roles filter button
 * takes on a tinted/active visual state whenever any role is hidden, so
 * being in a filtered view is always visible at a glance rather than only
 * discoverable by reading the "x of y roles" count.
 *
 * Role columns (and their header) are only rendered once at least one
 * module is actually expanded (`anyModuleOpen`). At the fully-collapsed
 * level there's nothing to check off yet, so showing 15 role headers with
 * no data under them was pure width cost for no information — the table
 * collapses to a single "Permission / Function" column until something is
 * opened. The background-continuation `<td>` on header rows is likewise
 * only rendered when `anyModuleOpen` is true, matching that same rule.
 */
export function PermissionMatrix() {
  const roles = useRolesStore((state) => state.roles)
  const fetchRolePermissions = useRolesStore(
    (state) => state.fetchRolePermissions
  )
  const { groups: permissionGroups, loading: catalogLoading } =
    usePermissionCatalog()
  const [area, setArea] = useState(ALL_AREAS)
  const [grantsLoading, setGrantsLoading] = useState(roles.length > 0)
  // Rows are grouped two levels deep (module, then action-with-children) and
  // both start collapsed — 32 modules x ~92 actions is far too much to lay
  // out flat by default. Keyed by moduleCode / action id.
  const [openModules, setOpenModules] = useState<Record<string, boolean>>({})
  const [openActions, setOpenActions] = useState<Record<string, boolean>>({})

  // Which roles are hidden from the matrix's columns. Absence of a key
  // means visible — that way newly-added roles show up by default instead
  // of silently being excluded.
  const [hiddenRoleIds, setHiddenRoleIds] = useState<Record<string, boolean>>(
    {}
  )
  const [roleFilterOpen, setRoleFilterOpen] = useState(false)
  const roleFilterRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    function handlePointerDown(event: MouseEvent) {
      if (
        roleFilterRef.current &&
        !roleFilterRef.current.contains(event.target as Node)
      ) {
        setRoleFilterOpen(false)
      }
    }
    document.addEventListener("mousedown", handlePointerDown)
    return () => document.removeEventListener("mousedown", handlePointerDown)
  }, [])

  const visibleRoles = useMemo(
    () => roles.filter((role) => !hiddenRoleIds[role.id]),
    [roles, hiddenRoleIds]
  )

  // `fetchRoles` doesn't fetch each role's permissions (see
  // stores/roles-store.ts's doc comment on `fromBackend`) — a full Roles ×
  // Permissions grid needs every one of them, so pull them in parallel
  // once the role list itself has loaded. Fetches for *every* role, not
  // just `visibleRoles` — hiding a role from the column filter shouldn't
  // skip loading its grants, since toggling it back visible should show
  // real data immediately rather than triggering a fresh fetch. Re-runs
  // if the set of role ids changes (e.g. a role gets added elsewhere) but
  // not on every render.
  const roleIdsKey = roles.map((r) => r.id).join(",")
  // Loading flag reset for a new set of role ids during render, against the
  // last-seen key; the effect below only does the fetching.
  const [prevRoleIdsKey, setPrevRoleIdsKey] = useState(roleIdsKey)
  if (roleIdsKey !== prevRoleIdsKey) {
    setPrevRoleIdsKey(roleIdsKey)
    setGrantsLoading(roles.length > 0)
  }
  useEffect(() => {
    if (roles.length === 0) return
    let cancelled = false
    Promise.all(roles.map((role) => fetchRolePermissions(role.id))).then(() => {
      if (!cancelled) setGrantsLoading(false)
    })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [roleIdsKey])

  const visibleGroups = useMemo(
    () =>
      area === ALL_AREAS
        ? permissionGroups
        : permissionGroups.filter((group) => group.module === area),
    [area, permissionGroups]
  )

  // Headers/data columns only make sense once something is expanded —
  // otherwise every row is just a collapsed module heading with nothing to
  // check off yet.
  const anyModuleOpen =
    area !== ALL_AREAS || Object.values(openModules).some(Boolean)

  const hiddenCount = roles.length - visibleRoles.length
  const permCount = visibleGroups.reduce(
    (n, g) => n + getLeafPermissionIds(g.permissions).length,
    0
  )

  function toggleModule(moduleCode: string) {
    setOpenModules((prev) => ({ ...prev, [moduleCode]: !prev[moduleCode] }))
  }
  function toggleAction(actionId: string) {
    setOpenActions((prev) => ({ ...prev, [actionId]: !prev[actionId] }))
  }
  function toggleRoleVisible(roleId: string) {
    setHiddenRoleIds((prev) => ({ ...prev, [roleId]: !prev[roleId] }))
  }

  if (catalogLoading || grantsLoading) {
    return (
      <div className="flex items-center justify-center gap-2 rounded-lg border border-border bg-card py-16 text-[13.5px] text-muted-foreground shadow-[var(--ledger-shadow)]">
        <Loader2 size={16} className="animate-spin" />
        Loading the permission matrix…
      </div>
    )
  }

  return (
    <div className="rounded-lg border border-border bg-card px-[30px] py-[26px] shadow-[var(--ledger-shadow)]">
      {/* Toolbar */}
      <div className="mb-5 flex flex-col gap-3 border-b border-border pb-4 sm:flex-row sm:items-center sm:justify-between">
        {/* Left: filters */}
        <div className="flex flex-wrap items-center gap-2">
          {/* Functional area */}
          <div className="relative max-w-full min-w-0">
            <select
              value={area}
              onChange={(event) => setArea(event.target.value)}
              className="h-9 max-w-full appearance-none rounded-lg border border-border bg-background pr-8 pl-3 text-[13px] font-medium text-foreground outline-none focus-visible:border-primary"
            >
              <option value={ALL_AREAS}>{ALL_AREAS}</option>
              {permissionGroups.map((group) => (
                <option key={group.moduleCode} value={group.module}>
                  {group.module} — {group.moduleCode}
                </option>
              ))}
            </select>
            <ChevronDown
              size={14}
              className="pointer-events-none absolute top-1/2 right-2.5 -translate-y-1/2 text-muted-foreground"
            />
          </div>

          <div className="h-5 w-px bg-border" />

          {/* Roles filter */}
          <div ref={roleFilterRef} className="relative">
            <button
              type="button"
              onClick={() => setRoleFilterOpen((v) => !v)}
              className={cn(
                "flex items-center gap-1.5 border transition-colors h-[38px] rounded-btn px-4 text-[13px] font-semibold justify-center",
                hiddenCount > 0
                  ? "border-primary/30 bg-primary/5 text-primary"
                  : "border-border bg-background text-foreground hover:bg-muted",
                roleFilterOpen && "border-primary/40"
              )}
            >
              <SlidersHorizontal size={13} />
              Roles
              <span
                className={cn(
                  "font-mono inline-flex h-6 items-center justify-center gap-1 rounded-btn px-2.5 text-[12px] font-semibold whitespace-nowrap",
                  hiddenCount > 0
                    ? "bg-primary/10 text-primary"
                    : "bg-muted text-muted-foreground"
                )}
              >
                {visibleRoles.length}/{roles.length}
              </span>
              <ChevronDown size={14} className="text-muted-foreground" />
            </button>
            {roleFilterOpen && (
              <div className="absolute top-[44px] left-0 z-30 w-[240px] rounded-btn border border-border bg-popover py-1.5 shadow-[var(--ledger-shadow)]">
                <div className="flex items-center justify-between border-b border-border px-3.5 pt-1 pb-2">
                  <span className="text-[11px] font-semibold tracking-wide text-muted-foreground uppercase">
                    Show roles
                  </span>
                  <div className="flex items-center gap-2.5">
                    <button
                      type="button"
                      onClick={() => setHiddenRoleIds({})}
                      className="text-[11px] font-semibold text-primary hover:underline"
                    >
                      All
                    </button>
                    <button
                      type="button"
                      onClick={() =>
                        setHiddenRoleIds(
                          Object.fromEntries(roles.map((r) => [r.id, true]))
                        )
                      }
                      className="text-[11px] font-semibold text-primary hover:underline"
                    >
                      None
                    </button>
                  </div>
                </div>
                <div className="max-h-[280px] overflow-y-auto">
                  {roles.map((role) => (
                    <label
                      key={role.id}
                      className="flex cursor-pointer items-center gap-2.5 px-3.5 py-[7px] text-[13px] text-foreground hover:bg-muted"
                    >
                      <input
                        type="checkbox"
                        checked={!hiddenRoleIds[role.id]}
                        onChange={() => toggleRoleVisible(role.id)}
                        className="size-[15px] accent-primary"
                      />
                      <span
                        className={
                          role.status === "Inactive" ? "opacity-50" : ""
                        }
                      >
                        {role.name}
                      </span>
                    </label>
                  ))}
                </div>
              </div>
            )}
          </div>

          {/* Active-filter reset, only shown when filtering something */}
          {(area !== ALL_AREAS || hiddenCount > 0) && (
            <button
              type="button"
              onClick={() => {
                setArea(ALL_AREAS)
                setHiddenRoleIds({})
              }}
              className="text-[12px] font-medium text-muted-foreground hover:text-foreground hover:underline"
            >
              Clear filters
            </button>
          )}
        </div>

        {/* Right: stats + view controls */}
        <div className="flex items-center gap-3">
          <span className="text-[12px] text-muted-foreground">
            <span className="font-semibold text-foreground">
              {visibleRoles.length}
            </span>{" "}
            of {roles.length} roles ·{" "}
            <span className="font-semibold text-foreground">{permCount}</span>{" "}
            permissions
          </span>

          <div className="flex items-center rounded-lg border border-border p-0.5">
            <button
              type="button"
              onClick={() => {
                setOpenModules(
                  Object.fromEntries(
                    visibleGroups.map((g) => [g.moduleCode, true])
                  )
                )
              }}
              className="rounded-btn px-2.5 py-1.5 text-[12px] font-medium text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
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
              className="rounded-btn px-2.5 py-1.5 text-[12px] font-medium text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
            >
              Collapse all
            </button>
          </div>
        </div>
      </div>

      {/* Matrix — horizontal scroll only; vertical scroll is the page's */}
      <div className="overflow-x-auto rounded-lg border border-border bg-card">
        <table
          className="w-full border-collapse"
          style={{
            minWidth: anyModuleOpen
              ? COL1_WIDTH + visibleRoles.length * 150
              : undefined,
          }}
        >
          <thead>
            <tr className="border-b border-border bg-muted">
              <th
                className="sticky left-0 z-20 bg-muted px-3.5 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase"
                style={{
                  width: COL1_WIDTH,
                  minWidth: COL1_WIDTH,
                  maxWidth: COL1_WIDTH,
                }}
              >
                Permission / Function
              </th>
              {anyModuleOpen &&
                visibleRoles.map((role) => (
                  <th
                    key={role.id}
                    className="min-w-[150px] bg-muted px-3 py-2.5 text-center text-[11px] font-semibold tracking-[0.04em] whitespace-nowrap text-muted-foreground uppercase"
                  >
                    <span
                      className={role.status === "Inactive" ? "opacity-50" : ""}
                      title={role.name}
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
                        className="sticky left-0 z-10 bg-muted/60 px-3.5 py-2"
                        style={{
                          width: COL1_WIDTH,
                          minWidth: COL1_WIDTH,
                          maxWidth: COL1_WIDTH,
                        }}
                      >
                        <button
                          type="button"
                          onClick={() => toggleModule(group.moduleCode)}
                          className="flex w-full items-start gap-2 text-left"
                        >
                          <ChevronRight
                            size={12}
                            className={cn(
                              "mt-0.5 shrink-0 text-muted-foreground transition-transform",
                              moduleOpen && "rotate-90"
                            )}
                          />
                          <span className="min-w-0 flex-1 text-[10.5px] leading-snug tracking-[0.06em] break-words whitespace-normal uppercase">
                            <span className="font-semibold text-foreground">
                              {group.module}
                            </span>{" "}
                            <span className="font-medium text-muted-foreground">
                              {group.moduleCode}
                            </span>
                          </span>
                          <span className="mt-0.5 shrink-0 bg-background font-mono inline-flex h-6 items-center justify-center gap-1 rounded-btn px-2.5 text-[12px] font-semibold whitespace-nowrap text-muted-foreground">
                            {groupIds.length}
                          </span>
                        </button>
                      </td>
                      {anyModuleOpen && (
                        <td
                          colSpan={visibleRoles.length}
                          className="bg-muted/60"
                        />
                      )}
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
                                className="sticky left-0 z-10 bg-muted/40 py-2 pr-3.5 pl-7"
                                style={{
                                  width: COL1_WIDTH,
                                  minWidth: COL1_WIDTH,
                                  maxWidth: COL1_WIDTH,
                                }}
                              >
                                <button
                                  type="button"
                                  onClick={() => toggleAction(permission.id)}
                                  className="flex w-full items-start gap-2 text-left"
                                >
                                  <ChevronRight
                                    size={12}
                                    className={cn(
                                      "mt-0.5 shrink-0 text-muted-foreground transition-transform",
                                      actionOpen && "rotate-90"
                                    )}
                                  />
                                  <span className="min-w-0 flex-1 text-[12px] leading-snug font-semibold break-words whitespace-normal text-foreground">
                                    {permission.label}
                                  </span>
                                  <span className="mt-0.5 shrink-0 bg-background font-mono inline-flex h-6 items-center justify-center gap-1 rounded-btn px-2.5 text-[12px] font-semibold whitespace-nowrap text-muted-foreground">
                                    {permission.permissions!.length}
                                  </span>
                                </button>
                              </td>
                              {anyModuleOpen && (
                                <td
                                  colSpan={visibleRoles.length}
                                  className="bg-muted/40"
                                />
                              )}
                            </tr>
                            {actionOpen &&
                              permission.permissions!.map((child) => (
                                <tr
                                  key={child.id}
                                  className="border-b border-border last:border-b-0 hover:bg-muted/50"
                                >
                                  <td
                                    className="sticky left-0 z-10 bg-card py-2.5 pr-3.5 pl-10 text-[13px] break-words whitespace-normal text-foreground"
                                    style={{
                                      width: COL1_WIDTH,
                                      minWidth: COL1_WIDTH,
                                      maxWidth: COL1_WIDTH,
                                    }}
                                    title={child.label}
                                  >
                                    {child.label}
                                  </td>
                                  {visibleRoles.map((role) => {
                                    const granted = role.permissions.includes(
                                      child.id
                                    )
                                    return (
                                      <td
                                        key={role.id}
                                        className="min-w-[150px] px-3 py-2.5 text-center"
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
                          <td
                            className="sticky left-0 z-10 bg-card py-2.5 pr-3.5 pl-7 text-[13px] break-words whitespace-normal text-foreground"
                            style={{
                              width: COL1_WIDTH,
                              minWidth: COL1_WIDTH,
                              maxWidth: COL1_WIDTH,
                            }}
                            title={permission.label}
                          >
                            {permission.label}
                          </td>
                          {visibleRoles.map((role) => {
                            const granted = role.permissions.includes(
                              permission.id
                            )
                            return (
                              <td
                                key={role.id}
                                className="min-w-[150px] px-3 py-2.5 text-center"
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
        collapsed; click a row, or use Expand all, to see individual permissions
        and the role columns. Use the Roles filter to hide columns you
        don&apos;t need right now.
      </p>
    </div>
  )
}
