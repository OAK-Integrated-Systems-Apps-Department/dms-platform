import { create } from "zustand"

import type { RoleFormValues } from "@/components/roles/schema"
import type { RoleRecord, RoleStatus } from "@/components/roles/types"
import { API_BASE_URL } from "@/lib/api-config"
import { ApiError, friendlyMessage } from "@/lib/api-error"
import { apiFetch, apiFetchJson } from "@/lib/api-fetch"
import { usePermissionsStore } from "@/stores/permissions-store"

/** Shape returned by `RoleResponse.java`. */
interface BackendRoleResponse {
  roleId: string
  tenantId: string
  roleName: string
  description: string | null
  status: "active" | "inactive"
  createdAt: string
  updatedAt: string | null
}

/** Shape returned by `RolePermissionResponse.java`. */
interface BackendRolePermissionResponse {
  rolePermissionId: string
  tenantId: string
  roleId: string
  permissionId: string
  permissionCode: string
  resourceScope: string | null
  createdAt: string
}

/** A single permission grant on a role, as tracked locally for revocation (needs `rolePermissionId`, not just the permission code). */
interface RolePermissionGrant {
  rolePermissionId: string
  permissionId: string
  permissionCode: string
}

function toRoleStatus(status: BackendRoleResponse["status"]): RoleStatus {
  return status === "active" ? "Active" : "Inactive"
}

function toBackendStatus(status: RoleStatus): "active" | "inactive" {
  return status === "Active" ? "active" : "inactive"
}

function fromBackendGrant(g: BackendRolePermissionResponse): RolePermissionGrant {
  return {
    rolePermissionId: g.rolePermissionId,
    permissionId: g.permissionId,
    permissionCode: g.permissionCode,
  }
}

/**
 * Maps a backend role onto a `RoleRecord`, preserving `assignedUsers` /
 * `system` from whatever local copy already exists, and `permissions`
 * from whatever local copy already exists *until* `fetchRolePermissions`
 * overwrites it with the real granted set.
 *
 * `permissions` used to be entirely local-only (see the old KNOWN GAP note
 * this replaces) — it's now backed by the real `role_permissions` table
 * via `GET /api/v1/roles/{roleId}/permissions`, seeded by
 * `V10__seed_fine_grained_permissions.sql`. `fetchRole` calls that
 * automatically; `fetchRoles` (the list screen) deliberately does not —
 * fetching every role's full grant list on every page load would be an
 * N+1 call for a field the list table doesn't even display. Until a role
 * has had its permissions individually fetched at least once (Role
 * Details, Edit Role, or Role Permission Configuration), `permissions`
 * here is just whatever was last known locally, same as before.
 *
 * `assignedUsers` and `system` remain genuinely local-only — the backend
 * still has no assigned-user count on `RoleResponse` and no "system role"
 * concept at all (see the same caveats noted when Role CRUD was wired up).
 */
function fromBackend(role: BackendRoleResponse, existing?: RoleRecord): RoleRecord {
  return {
    id: role.roleId,
    name: role.roleName,
    description: role.description ?? "",
    status: toRoleStatus(role.status),
    assignedUsers: existing?.assignedUsers ?? 0,
    permissions: existing?.permissions ?? [],
    system: existing?.system ?? false,
  }
}

interface RolesState {
  roles: RoleRecord[]
  /** roleId -> that role's current permission grants, incl. `rolePermissionId` for revocation. Populated by `fetchRolePermissions`. */
  roleGrants: Record<string, RolePermissionGrant[]>
  loading: boolean
  error: string | null

  /** GET /api/v1/roles — populates the list (Role List screen). Does NOT fetch each role's permissions — see `fromBackend`'s doc comment. */
  fetchRoles: () => Promise<void>
  /**
   * GET /api/v1/roles/{roleId} — resolves a single role, for direct links
   * / refreshes into Role Details, Edit Role, or Permission Configuration
   * that land before `fetchRoles` has ever populated the store. Also
   * fetches that role's real granted permissions. Returns the resolved
   * role, or `null` if it doesn't exist / isn't accessible.
   */
  fetchRole: (id: string) => Promise<RoleRecord | null>
  /**
   * GET /api/v1/roles/{roleId}/permissions — resolves a role's currently
   * granted permission codes, and patches both `roles` (the `permissions`
   * field) and `roleGrants` (for `syncRolePermissions` to diff against).
   * Exposed on its own (not just via `fetchRole`) so the Permission
   * Matrix can pull every visible role's grants in parallel without
   * re-fetching each role's name/description/status too.
   */
  fetchRolePermissions: (roleId: string) => Promise<{ codes: string[]; error: string | null }>

  /** POST /api/v1/roles, then grants any initially-selected permissions. Returns the created role, or an error message. */
  addRole: (
    values: RoleFormValues
  ) => Promise<{ role: RoleRecord | null; error: string | null }>
  /** PUT /api/v1/roles/{id}. Returns the updated role, or an error message. Does not touch permissions — call `syncRolePermissions` separately. */
  updateRole: (
    id: string,
    values: RoleFormValues
  ) => Promise<{ role: RoleRecord | null; error: string | null }>
  /**
   * Reconciles a role's real permission grants to match `desiredCodes`:
   * re-fetches the role's current grants, diffs against `desiredCodes`,
   * and issues the necessary `POST`/`DELETE` calls against
   * `/api/v1/roles/{roleId}/permissions` in parallel. Always re-fetches
   * the real post-sync state before resolving (win or lose), so
   * `RoleRecord.permissions` never reflects a desired-but-not-actually-
   * applied set. Returns a single combined error message if any of the
   * grant/revoke calls failed, or null if everything applied cleanly.
   */
  syncRolePermissions: (
    roleId: string,
    desiredCodes: string[]
  ) => Promise<{ error: string | null }>
  /** PUT /api/v1/roles/{id}, resending the role's current name/description with status flipped. */
  toggleStatus: (id: string) => Promise<{ error: string | null }>
  /** DELETE /api/v1/roles/{id} (soft-delete). */
  deleteRole: (id: string) => Promise<{ error: string | null }>
}

/**
 * Role store, backed by the real `/api/v1/roles` and
 * `/api/v1/roles/{roleId}/permissions` endpoints (RoleController /
 * RoleServiceImpl / RolePermissionController / RolePermissionServiceImpl).
 * Previously this held an in-memory copy of `components/roles/data.ts`'s
 * `initialRoles` and every action mutated it directly; that mock array is
 * no longer read here at all, though it's still imported elsewhere (e.g.
 * the role picker in User Invite/Edit, which isn't part of this
 * integration) — see that file for the "used elsewhere" note before
 * removing it.
 */
export const useRolesStore = create<RolesState>((set, get) => ({
  roles: [],
  roleGrants: {},
  loading: false,
  error: null,

  fetchRoles: async () => {
    set({ loading: true, error: null })
    let body: { content: BackendRoleResponse[] }
    try {
      // No pagination endpoint params exposed by the Role List UI yet —
      // it paginates client-side over the full set (see
      // app/admin/roles/page.tsx's TablePagination) — so this asks for a
      // page large enough to cover realistic tenants in one call.
      body = await apiFetchJson(`${API_BASE_URL}/api/v1/roles?size=200`, undefined, "Could not load roles.")
    } catch (err) {
      set({ loading: false, error: friendlyMessage(err) })
      return
    }

    set((state) => {
      const existingById = new Map(state.roles.map((role) => [role.id, role]))
      return {
        loading: false,
        error: null,
        roles: body.content.map((role) => fromBackend(role, existingById.get(role.roleId))),
      }
    })
  },

  fetchRole: async (id) => {
    let body: BackendRoleResponse
    try {
      body = await apiFetchJson(`${API_BASE_URL}/api/v1/roles/${id}`, undefined, "Could not load this role.")
    } catch (err) {
      if (!(err instanceof ApiError && err.status === 404)) {
        set({ error: friendlyMessage(err) })
      }
      return null
    }

    const existing = get().roles.find((role) => role.id === id)
    const resolved = fromBackend(body, existing)

    set((state) => ({
      roles: existing
        ? state.roles.map((role) => (role.id === id ? resolved : role))
        : [resolved, ...state.roles],
    }))

    // RoleResponse doesn't carry permissions (see `fromBackend`'s doc
    // comment) — fetch the real granted set separately. Non-fatal if this
    // part fails: the role itself loaded fine, so surface the error but
    // still return what we have rather than treating the whole fetch as
    // failed.
    const { error: permError } = await get().fetchRolePermissions(id)
    if (permError) set({ error: permError })
    return get().roles.find((role) => role.id === id) ?? resolved
  },

  fetchRolePermissions: async (roleId) => {
    let body: BackendRolePermissionResponse[]
    try {
      body = await apiFetchJson(
        `${API_BASE_URL}/api/v1/roles/${roleId}/permissions`,
        undefined,
        "Could not load this role's granted permissions."
      )
    } catch (err) {
      return { codes: [], error: friendlyMessage(err) }
    }

    const grants = body.map(fromBackendGrant)
    const codes = grants.map((g) => g.permissionCode)

    set((state) => ({
      roleGrants: { ...state.roleGrants, [roleId]: grants },
      roles: state.roles.map((role) =>
        role.id === roleId ? { ...role, permissions: codes } : role
      ),
    }))

    return { codes, error: null }
  },

  addRole: async (values) => {
    let body: BackendRoleResponse
    try {
      body = await apiFetchJson(
        `${API_BASE_URL}/api/v1/roles`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            roleName: values.name,
            description: values.description || undefined,
            status: toBackendStatus(values.status),
          }),
        },
        "Could not create the role."
      )
    } catch (err) {
      return { role: null, error: friendlyMessage(err) }
    }

    const role = fromBackend(body)
    set((state) => ({ roles: [role, ...state.roles] }))

    // CreateRoleRequest has no permissions field (see GrantPermissionRequest —
    // grants are a separate call, and need a roleId that only exists after
    // this point) — so anything selected on the Add Role form is applied
    // as a second step, once the role itself exists.
    if (values.permissions.length > 0) {
      const { error: permError } = await get().syncRolePermissions(body.roleId, values.permissions)
      if (permError) {
        return {
          role: get().roles.find((r) => r.id === body.roleId) ?? role,
          error: `The role "${values.name}" was created, but its permissions could not be fully applied: ${permError} You can finish assigning them from the role's Edit page.`,
        }
      }
    }

    return { role: get().roles.find((r) => r.id === body.roleId) ?? role, error: null }
  },

  updateRole: async (id, values) => {
    let body: BackendRoleResponse
    try {
      body = await apiFetchJson(
        `${API_BASE_URL}/api/v1/roles/${id}`,
        {
          method: "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            roleName: values.name,
            description: values.description || undefined,
            status: toBackendStatus(values.status),
          }),
        },
        "Could not save changes."
      )
    } catch (err) {
      return { role: null, error: friendlyMessage(err) }
    }

    let updated: RoleRecord = fromBackend(body)
    set((state) => ({
      roles: state.roles.map((role) => {
        if (role.id !== id) return role
        // Permissions are synced separately (`syncRolePermissions`) by
        // whichever screen called this — carry the existing selection
        // forward here rather than clearing it.
        updated = { ...fromBackend(body, role), permissions: role.permissions }
        return updated
      }),
    }))
    return { role: updated, error: null }
  },

  syncRolePermissions: async (roleId, desiredCodes) => {
    // Re-fetch current grants fresh rather than trusting whatever's
    // cached in `roleGrants` — a stale local diff could under- or
    // over-apply changes another tab/session already made.
    const { codes: currentCodes, error: fetchError } = await get().fetchRolePermissions(roleId)
    if (fetchError) return { error: fetchError }

    const currentGrants = get().roleGrants[roleId] ?? []
    const currentSet = new Set(currentCodes)
    const desiredSet = new Set(desiredCodes)

    const toGrant = desiredCodes.filter((code) => !currentSet.has(code))
    const toRevoke = currentGrants.filter((grant) => !desiredSet.has(grant.permissionCode))

    if (toGrant.length === 0 && toRevoke.length === 0) {
      return { error: null }
    }

    // Resolve permission code -> permissionId via the Permission Catalog
    // store's flat list — every screen that calls this has already
    // triggered a fetch of it via `components/roles/use-permission-catalog.ts`.
    const permissionIdByCode = new Map(
      usePermissionsStore.getState().permissions.map((p) => [p.code, p.id])
    )

    const grantResults = toGrant.map(async (code) => {
      const permissionId = permissionIdByCode.get(code)
      if (!permissionId) {
        throw new Error(`"${code}" isn't in the current permission catalog.`)
      }
      await apiFetch(
        `${API_BASE_URL}/api/v1/roles/${roleId}/permissions`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ permissionId }),
        },
        `Could not grant "${code}".`
      )
    })

    const revokeResults = toRevoke.map(async (grant) => {
      await apiFetch(
        `${API_BASE_URL}/api/v1/roles/${roleId}/permissions/${grant.rolePermissionId}`,
        { method: "DELETE" },
        `Could not revoke "${grant.permissionCode}".`
      )
    })

    const results = await Promise.allSettled([...grantResults, ...revokeResults])

    // Re-fetch the real post-sync state regardless of outcome, so
    // `RoleRecord.permissions` always reflects what's actually granted
    // server-side rather than the locally-desired set, which may only be
    // partially applied if something below failed.
    await get().fetchRolePermissions(roleId)

    const failures = results.filter(
      (r): r is PromiseRejectedResult => r.status === "rejected"
    )
    if (failures.length === 0) return { error: null }
    if (failures.length === 1) {
      const reason = failures[0].reason
      return { error: reason instanceof Error ? reason.message : "Could not update this role's permissions." }
    }
    return { error: `${failures.length} permission changes could not be applied. Please review and try again.` }
  },

  toggleStatus: async (id) => {
    const role = get().roles.find((r) => r.id === id)
    if (!role) return { error: "Role not found." }

    const nextStatus: RoleStatus = role.status === "Active" ? "Inactive" : "Active"

    try {
      await apiFetch(
        `${API_BASE_URL}/api/v1/roles/${id}`,
        {
          method: "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            roleName: role.name,
            description: role.description || undefined,
            status: toBackendStatus(nextStatus),
          }),
        },
        "Could not update the role's status."
      )
    } catch (err) {
      return { error: friendlyMessage(err) }
    }

    set((state) => ({
      roles: state.roles.map((r) => (r.id === id ? { ...r, status: nextStatus } : r)),
    }))
    return { error: null }
  },

  deleteRole: async (id) => {
    try {
      await apiFetch(`${API_BASE_URL}/api/v1/roles/${id}`, { method: "DELETE" }, "Could not delete the role.")
    } catch (err) {
      return { error: friendlyMessage(err) }
    }

    set((state) => ({ roles: state.roles.filter((role) => role.id !== id) }))
    return { error: null }
  },
}))
