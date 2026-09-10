import { create } from "zustand"

import type { PermissionFormValues } from "@/components/permissions/schema"
import type {
  PermissionActionRecord,
  PermissionModuleRecord,
  PermissionRecord,
} from "@/components/permissions/types"
import { API_BASE_URL } from "@/lib/api-config"

/** Shape returned by `PermissionResponse.java`. */
interface BackendPermissionResponse {
  permissionId: string
  permissionCode: string
  name: string
  description: string | null
  shared: boolean
  actionId: string
  actionCode: string
  moduleId: string
  moduleCode: string
  createdAt: string
  updatedAt: string | null
}

/** Shape returned by `PermissionActionResponse.java`. */
interface BackendPermissionActionResponse {
  actionId: string
  moduleId: string
  moduleCode: string
  actionCode: string
  actionName: string
  description: string | null
  createdAt: string
  updatedAt: string | null
}

/** Shape returned by `PermissionModuleResponse.java`. */
interface BackendPermissionModuleResponse {
  moduleId: string
  moduleCode: string
  moduleName: string
  description: string | null
  createdAt: string
  updatedAt: string | null
}

function fromBackendPermission(p: BackendPermissionResponse): PermissionRecord {
  return {
    id: p.permissionId,
    code: p.permissionCode,
    name: p.name,
    description: p.description ?? "",
    shared: p.shared,
    actionId: p.actionId,
    actionCode: p.actionCode,
    moduleId: p.moduleId,
    moduleCode: p.moduleCode,
  }
}

function fromBackendAction(a: BackendPermissionActionResponse): PermissionActionRecord {
  return {
    id: a.actionId,
    moduleId: a.moduleId,
    moduleCode: a.moduleCode,
    code: a.actionCode,
    name: a.actionName,
    description: a.description ?? "",
  }
}

function fromBackendModule(m: BackendPermissionModuleResponse): PermissionModuleRecord {
  return {
    id: m.moduleId,
    code: m.moduleCode,
    name: m.moduleName,
    description: m.description ?? "",
  }
}

/** Distinguishes "server responded with an error" from "never reached the server" — same pattern used across the app's other stores/forms. */
async function parseErrorMessage(response: Response, fallback: string): Promise<string> {
  if (response.status === 403) {
    return "You don't have permission to do that."
  }
  const body = await response.json().catch(() => null)
  return body?.message ?? fallback
}

const NETWORK_ERROR =
  "Could not reach the server. Check that the backend is running and try again."

interface PermissionsState {
  permissions: PermissionRecord[]
  modules: PermissionModuleRecord[]
  actions: PermissionActionRecord[]
  loading: boolean
  /** True once modules + actions have loaded at least once, so the Add/Edit Permission form knows whether to fetch them itself or reuse what's already here. */
  catalogLoaded: boolean
  error: string | null

  /** GET /api/v1/permissions — populates the Permission List screen. */
  fetchPermissions: () => Promise<void>
  /** GET /api/v1/permissions/{id} — resolves a single permission for direct links into View/Edit Permission. Returns null if it doesn't exist. */
  fetchPermission: (id: string) => Promise<PermissionRecord | null>
  /** GET /api/v1/permission-modules + GET /api/v1/permission-actions — populates the Add/Edit Permission form's cascading Module → Action selects. */
  fetchCatalog: () => Promise<void>

  /** POST /api/v1/permissions. Returns the created permission, or an error message. */
  addPermission: (
    values: PermissionFormValues
  ) => Promise<{ permission: PermissionRecord | null; error: string | null }>
  /** PUT /api/v1/permissions/{id}. Returns the updated permission, or an error message. */
  updatePermission: (
    id: string,
    values: PermissionFormValues
  ) => Promise<{ permission: PermissionRecord | null; error: string | null }>
  /** DELETE /api/v1/permissions/{id} (soft-delete; backend rejects this while any role still holds the permission). */
  deletePermission: (id: string) => Promise<{ error: string | null }>
}

/**
 * Permission catalog store, backed by the real `/api/v1/permissions`,
 * `/api/v1/permission-actions`, and `/api/v1/permission-modules`
 * endpoints. Distinct from the permission *picker* on the Add/Edit Role
 * and Role Permission Configuration screens (`components/roles/data.ts`'s
 * `permissionGroups`), which is a separate, still-mocked concern — see the
 * KNOWN GAP note in `stores/roles-store.ts`. This store manages the
 * catalog itself (creating/editing/retiring permissions and the
 * modules/actions they belong to); that one manages which of an existing
 * catalog's permissions a given role holds. They'll eventually need to
 * read from the same source, but merging them isn't part of this change.
 */
export const usePermissionsStore = create<PermissionsState>((set, get) => ({
  permissions: [],
  modules: [],
  actions: [],
  loading: false,
  catalogLoaded: false,
  error: null,

  fetchPermissions: async () => {
    set({ loading: true, error: null })
    let response: Response
    try {
      // No pagination UI on Permission List yet — same tradeoff as the
      // Role List screen (see stores/roles-store.ts): ask for a page
      // large enough to cover the whole catalog in one call.
      response = await fetch(`${API_BASE_URL}/api/v1/permissions?size=500`, {
        credentials: "include",
      })
    } catch {
      set({ loading: false, error: NETWORK_ERROR })
      return
    }

    if (!response.ok) {
      set({
        loading: false,
        error: await parseErrorMessage(response, "Could not load the permission catalog."),
      })
      return
    }

    const body: { content: BackendPermissionResponse[] } = await response.json()
    set({
      loading: false,
      error: null,
      permissions: body.content.map(fromBackendPermission),
    })
  },

  fetchPermission: async (id) => {
    let response: Response
    try {
      response = await fetch(`${API_BASE_URL}/api/v1/permissions/${id}`, {
        credentials: "include",
      })
    } catch {
      set({ error: NETWORK_ERROR })
      return null
    }

    if (!response.ok) {
      if (response.status !== 404) {
        set({ error: await parseErrorMessage(response, "Could not load this permission.") })
      }
      return null
    }

    const body: BackendPermissionResponse = await response.json()
    const resolved = fromBackendPermission(body)

    set((state) => {
      const exists = state.permissions.some((p) => p.id === id)
      return {
        permissions: exists
          ? state.permissions.map((p) => (p.id === id ? resolved : p))
          : [resolved, ...state.permissions],
      }
    })
    return resolved
  },

  fetchCatalog: async () => {
    let modulesResponse: Response
    let actionsResponse: Response
    try {
      ;[modulesResponse, actionsResponse] = await Promise.all([
        fetch(`${API_BASE_URL}/api/v1/permission-modules?size=200`, {
          credentials: "include",
        }),
        // Every action across every module — fine at this catalog's scale
        // (dozens, not thousands) and lets the Action select filter
        // client-side instantly on module change rather than re-fetching
        // via the `/by-module/{moduleId}` endpoint on every keystroke of
        // "which module did I pick".
        fetch(`${API_BASE_URL}/api/v1/permission-actions?size=1000`, {
          credentials: "include",
        }),
      ])
    } catch {
      set({ error: NETWORK_ERROR })
      return
    }

    if (!modulesResponse.ok || !actionsResponse.ok) {
      const failed = modulesResponse.ok ? actionsResponse : modulesResponse
      set({ error: await parseErrorMessage(failed, "Could not load modules and actions.") })
      return
    }

    const modulesBody: { content: BackendPermissionModuleResponse[] } = await modulesResponse.json()
    const actionsBody: { content: BackendPermissionActionResponse[] } = await actionsResponse.json()

    set({
      modules: modulesBody.content
        .map(fromBackendModule)
        .sort((a, b) => a.name.localeCompare(b.name)),
      actions: actionsBody.content.map(fromBackendAction),
      catalogLoaded: true,
      error: null,
    })
  },

  addPermission: async (values) => {
    let response: Response
    try {
      response = await fetch(`${API_BASE_URL}/api/v1/permissions`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "include",
        body: JSON.stringify({
          permissionCode: values.code,
          name: values.name,
          description: values.description || undefined,
          actionId: values.actionId,
        }),
      })
    } catch {
      return { permission: null, error: NETWORK_ERROR }
    }

    if (!response.ok) {
      return {
        permission: null,
        error: await parseErrorMessage(response, "Could not create the permission."),
      }
    }

    const body: BackendPermissionResponse = await response.json()
    const permission = fromBackendPermission(body)
    set((state) => ({ permissions: [permission, ...state.permissions] }))
    return { permission, error: null }
  },

  updatePermission: async (id, values) => {
    let response: Response
    try {
      // permissionCode is deliberately not sent — UpdatePermissionRequest
      // doesn't accept it (see the record's own doc comment: it's matched
      // against @authz.hasPermission(...) checks and existing grants
      // throughout the backend, so it's immutable after creation).
      response = await fetch(`${API_BASE_URL}/api/v1/permissions/${id}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        credentials: "include",
        body: JSON.stringify({
          name: values.name,
          description: values.description || undefined,
          actionId: values.actionId,
        }),
      })
    } catch {
      return { permission: null, error: NETWORK_ERROR }
    }

    if (!response.ok) {
      return {
        permission: null,
        error: await parseErrorMessage(response, "Could not save changes."),
      }
    }

    const body: BackendPermissionResponse = await response.json()
    const permission = fromBackendPermission(body)
    set((state) => ({
      permissions: state.permissions.map((p) => (p.id === id ? permission : p)),
    }))
    return { permission, error: null }
  },

  deletePermission: async (id) => {
    let response: Response
    try {
      response = await fetch(`${API_BASE_URL}/api/v1/permissions/${id}`, {
        method: "DELETE",
        credentials: "include",
      })
    } catch {
      return { error: NETWORK_ERROR }
    }

    if (!response.ok) {
      // The backend now rejects this (409-ish, via DuplicateResourceException)
      // while any role still holds the permission — see the guard added to
      // PermissionServiceImpl.deletePermission — so this message is the
      // most common failure case in practice, not just a generic fallback.
      return {
        error: await parseErrorMessage(
          response,
          "Could not delete the permission. It may still be granted to a role."
        ),
      }
    }

    set((state) => ({ permissions: state.permissions.filter((p) => p.id !== id) }))
    return { error: null }
  },
}))
