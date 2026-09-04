import { create } from "zustand"

import type { RoleFormValues } from "@/components/roles/schema"
import type { RoleRecord, RoleStatus } from "@/components/roles/types"
import { API_BASE_URL } from "@/lib/api-config"

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

function toRoleStatus(status: BackendRoleResponse["status"]): RoleStatus {
  return status === "active" ? "Active" : "Inactive"
}

function toBackendStatus(status: RoleStatus): "active" | "inactive" {
  return status === "Active" ? "active" : "inactive"
}

/**
 * Maps a backend role onto a `RoleRecord`, preserving `permissions` /
 * `assignedUsers` / `system` from whatever local copy already exists.
 *
 * KNOWN GAP: `RoleResponse` doesn't carry permissions or an assigned-user
 * count yet (see RoleResponse.java) — those still live only in this
 * store, set by the standalone Role Permission Configuration screen and
 * never sent to the backend, per the earlier note that permission storage
 * isn't wired up yet. Re-fetching a role must not silently wipe that
 * local-only data back to `[]` / `0`, so it's carried forward from
 * `existing` when present. Likewise `system` — the backend has no
 * "built-in role" concept at all right now, so every role it returns
 * comes back editable/deletable; `existing?.system` only matters for
 * roles that were seeded in `components/roles/data.ts` and never touched
 * the backend.
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

/** Distinguishes "server responded with an error" from "never reached the server" — see the same pattern in UserEditForm/UserInviteForm. */
async function parseErrorMessage(response: Response, fallback: string): Promise<string> {
  if (response.status === 403) {
    return "You don't have permission to do that."
  }
  const body = await response.json().catch(() => null)
  return body?.message ?? fallback
}

const NETWORK_ERROR =
  "Could not reach the server. Check that the backend is running and try again."

interface RolesState {
  roles: RoleRecord[]
  loading: boolean
  error: string | null

  /** GET /api/v1/roles — populates the list (Role List screen). */
  fetchRoles: () => Promise<void>
  /**
   * GET /api/v1/roles/{roleId} — resolves a single role, for direct links
   * / refreshes into Role Details, Edit Role, or Permission Configuration
   * that land before `fetchRoles` has ever populated the store. Returns
   * the resolved role, or `null` if it doesn't exist / isn't accessible.
   */
  fetchRole: (id: string) => Promise<RoleRecord | null>

  /** POST /api/v1/roles. Returns the created role, or an error message. */
  addRole: (
    values: RoleFormValues
  ) => Promise<{ role: RoleRecord | null; error: string | null }>
  /** PUT /api/v1/roles/{id}. Returns the updated role, or an error message. */
  updateRole: (
    id: string,
    values: RoleFormValues
  ) => Promise<{ role: RoleRecord | null; error: string | null }>
  /** Local-only — see the KNOWN GAP note on `fromBackend` above. */
  updateRolePermissions: (id: string, permissions: string[]) => void
  /** PUT /api/v1/roles/{id}, resending the role's current name/description with status flipped. */
  toggleStatus: (id: string) => Promise<{ error: string | null }>
  /** DELETE /api/v1/roles/{id} (soft-delete). */
  deleteRole: (id: string) => Promise<{ error: string | null }>
}

/**
 * Role store, backed by the real `/api/v1/roles` endpoints (RoleController
 * / RoleServiceImpl). Previously this held an in-memory copy of
 * `components/roles/data.ts`'s `initialRoles` and every action mutated it
 * directly; that mock array is no longer read here at all, though it's
 * still imported elsewhere (e.g. the role picker in User Invite/Edit,
 * which isn't part of this integration) — see that file for the "used
 * elsewhere" note before removing it.
 */
export const useRolesStore = create<RolesState>((set, get) => ({
  roles: [],
  loading: false,
  error: null,

  fetchRoles: async () => {
    set({ loading: true, error: null })
    let response: Response
    try {
      // No pagination endpoint params exposed by the Role List UI yet —
      // it paginates client-side over the full set (see
      // app/admin/roles/page.tsx's TablePagination) — so this asks for a
      // page large enough to cover realistic tenants in one call.
      response = await fetch(`${API_BASE_URL}/api/v1/roles?size=200`, {
        credentials: "include",
      })
    } catch {
      set({ loading: false, error: NETWORK_ERROR })
      return
    }

    if (!response.ok) {
      set({
        loading: false,
        error: await parseErrorMessage(response, "Could not load roles."),
      })
      return
    }

    const body: { content: BackendRoleResponse[] } = await response.json()
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
    let response: Response
    try {
      response = await fetch(`${API_BASE_URL}/api/v1/roles/${id}`, {
        credentials: "include",
      })
    } catch {
      set({ error: NETWORK_ERROR })
      return null
    }

    if (!response.ok) {
      if (response.status !== 404) {
        set({ error: await parseErrorMessage(response, "Could not load this role.") })
      }
      return null
    }

    const body: BackendRoleResponse = await response.json()
    const existing = get().roles.find((role) => role.id === id)
    const resolved = fromBackend(body, existing)

    set((state) => ({
      roles: existing
        ? state.roles.map((role) => (role.id === id ? resolved : role))
        : [resolved, ...state.roles],
    }))
    return resolved
  },

  addRole: async (values) => {
    let response: Response
    try {
      response = await fetch(`${API_BASE_URL}/api/v1/roles`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "include",
        body: JSON.stringify({
          roleName: values.name,
          description: values.description || undefined,
          status: toBackendStatus(values.status),
        }),
      })
    } catch {
      return { role: null, error: NETWORK_ERROR }
    }

    if (!response.ok) {
      return {
        role: null,
        error: await parseErrorMessage(response, "Could not create the role."),
      }
    }

    const body: BackendRoleResponse = await response.json()
    // Permissions chosen on the Add Role form aren't sent to the backend
    // (see the KNOWN GAP note on `fromBackend`) — kept locally so the new
    // role's summary still reflects what was picked.
    const role = fromBackend(body, { permissions: values.permissions } as RoleRecord)
    set((state) => ({ roles: [role, ...state.roles] }))
    return { role, error: null }
  },

  updateRole: async (id, values) => {
    let response: Response
    try {
      response = await fetch(`${API_BASE_URL}/api/v1/roles/${id}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        credentials: "include",
        body: JSON.stringify({
          roleName: values.name,
          description: values.description || undefined,
          status: toBackendStatus(values.status),
        }),
      })
    } catch {
      return { role: null, error: NETWORK_ERROR }
    }

    if (!response.ok) {
      return {
        role: null,
        error: await parseErrorMessage(response, "Could not save changes."),
      }
    }

    const body: BackendRoleResponse = await response.json()
    let updated: RoleRecord = fromBackend(body)
    set((state) => ({
      roles: state.roles.map((role) => {
        if (role.id !== id) return role
        // Permissions are edited on the separate Permission Configuration
        // screen, not this form — carry the existing selection forward
        // rather than clearing it (RoleFormValues.permissions isn't
        // touched by Edit Role's own fields).
        updated = { ...fromBackend(body, role), permissions: role.permissions }
        return updated
      }),
    }))
    return { role: updated, error: null }
  },

  updateRolePermissions: (id, permissions) => {
    set((state) => ({
      roles: state.roles.map((role) => (role.id === id ? { ...role, permissions } : role)),
    }))
  },

  toggleStatus: async (id) => {
    const role = get().roles.find((r) => r.id === id)
    if (!role) return { error: "Role not found." }

    const nextStatus: RoleStatus = role.status === "Active" ? "Inactive" : "Active"

    let response: Response
    try {
      response = await fetch(`${API_BASE_URL}/api/v1/roles/${id}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        credentials: "include",
        body: JSON.stringify({
          roleName: role.name,
          description: role.description || undefined,
          status: toBackendStatus(nextStatus),
        }),
      })
    } catch {
      return { error: NETWORK_ERROR }
    }

    if (!response.ok) {
      return { error: await parseErrorMessage(response, "Could not update the role's status.") }
    }

    set((state) => ({
      roles: state.roles.map((r) => (r.id === id ? { ...r, status: nextStatus } : r)),
    }))
    return { error: null }
  },

  deleteRole: async (id) => {
    let response: Response
    try {
      response = await fetch(`${API_BASE_URL}/api/v1/roles/${id}`, {
        method: "DELETE",
        credentials: "include",
      })
    } catch {
      return { error: NETWORK_ERROR }
    }

    if (!response.ok) {
      return { error: await parseErrorMessage(response, "Could not delete the role.") }
    }

    set((state) => ({ roles: state.roles.filter((role) => role.id !== id) }))
    return { error: null }
  },
}))
