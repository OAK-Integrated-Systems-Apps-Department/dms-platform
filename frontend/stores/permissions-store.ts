import { create } from "zustand"

import { humanizeCode } from "@/components/permissions/format"
import type {
  PermissionActionRecord,
  PermissionModuleRecord,
  PermissionRecord,
} from "@/components/permissions/types"
import { API_BASE_URL } from "@/lib/api-config"
import { apiFetchJson } from "@/lib/api-fetch"
import { ApiError, friendlyMessage } from "@/lib/api-error"

/** Shape returned by `PermissionResponse.java`. */
interface BackendPermissionResponse {
  permissionId: string
  permissionCode: string
  name: string
  description: string | null
  shared: boolean
  actionId: string
  actionCode: string
  actionName: string | null
  moduleId: string
  moduleCode: string
  moduleName: string | null
  deprecated: boolean
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
    actionName: p.actionName || humanizeCode(p.actionCode),
    moduleId: p.moduleId,
    moduleCode: p.moduleCode,
    moduleName: p.moduleName || humanizeCode(p.moduleCode),
    deprecated: p.deprecated,
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

interface PermissionsState {
  permissions: PermissionRecord[]
  modules: PermissionModuleRecord[]
  actions: PermissionActionRecord[]
  loading: boolean
  error: string | null

  /** GET /api/v1/permissions — populates the Permission List screen. */
  fetchPermissions: () => Promise<void>
  /** GET /api/v1/permissions/{id} — resolves a single permission for direct links into the read-only Permission Details screen. Returns null if it doesn't exist. */
  fetchPermission: (id: string) => Promise<PermissionRecord | null>
  /** GET /api/v1/permission-modules + GET /api/v1/permission-actions — populates the Module/Action grouping used by `usePermissionCatalog` (the Role Permission Configuration picker). */
  fetchCatalog: () => Promise<void>
}

/**
 * Permission catalog store, backed by the real `/api/v1/permissions`,
 * `/api/v1/permission-actions`, and `/api/v1/permission-modules`
 * endpoints. Read-only: permissions are a fixed, platform-managed set —
 * create/update/delete are PLATFORM_ADMIN-only in the backend (see
 * `PermissionController`) and there's no create/edit/delete UI for them
 * in this app anymore, so this store only ever fetches. Distinct from the
 * permission *picker* on the Add/Edit Role and Role Permission
 * Configuration screens (`components/roles/use-permission-catalog.ts`),
 * which reads `modules`/`actions`/`permissions` from here but is a
 * separate concern: this store manages the catalog's own data, that hook
 * groups it for the Role screens' checkbox picker.
 */
export const usePermissionsStore = create<PermissionsState>((set) => ({
  permissions: [],
  modules: [],
  actions: [],
  loading: false,
  error: null,

  fetchPermissions: async () => {
    set({ loading: true, error: null })
    let body: { content: BackendPermissionResponse[] }
    try {
      // No pagination UI on Permission List yet — same tradeoff as the
      // Role List screen (see stores/roles-store.ts): ask for a page
      // large enough to cover the whole catalog in one call.
      body = await apiFetchJson(
        `${API_BASE_URL}/api/v1/permissions?size=500`,
        undefined,
        "Could not load the permission catalog."
      )
    } catch (err) {
      set({ loading: false, error: friendlyMessage(err) })
      return
    }

    set({
      loading: false,
      error: null,
      permissions: body.content.map(fromBackendPermission),
    })
  },

  fetchPermission: async (id) => {
    let body: BackendPermissionResponse
    try {
      body = await apiFetchJson(
        `${API_BASE_URL}/api/v1/permissions/${id}`,
        undefined,
        "Could not load this permission."
      )
    } catch (err) {
      if (!(err instanceof ApiError && err.status === 404)) {
        set({ error: friendlyMessage(err) })
      }
      return null
    }

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
    let modulesBody: { content: BackendPermissionModuleResponse[] }
    let actionsBody: { content: BackendPermissionActionResponse[] }
    try {
      ;[modulesBody, actionsBody] = await Promise.all([
        apiFetchJson<{ content: BackendPermissionModuleResponse[] }>(
          `${API_BASE_URL}/api/v1/permission-modules?size=200`,
          undefined,
          "Could not load modules and actions."
        ),
        // Every action across every module — fine at this catalog's scale
        // (dozens, not thousands) and lets the Action select filter
        // client-side instantly on module change rather than re-fetching
        // via the `/by-module/{moduleId}` endpoint on every keystroke of
        // "which module did I pick".
        apiFetchJson<{ content: BackendPermissionActionResponse[] }>(
          `${API_BASE_URL}/api/v1/permission-actions?size=1000`,
          undefined,
          "Could not load modules and actions."
        ),
      ])
    } catch (err) {
      set({ error: friendlyMessage(err) })
      return
    }

    set({
      modules: modulesBody.content
        .map(fromBackendModule)
        .sort((a, b) => a.name.localeCompare(b.name)),
      actions: actionsBody.content.map(fromBackendAction),
      error: null,
    })
  },
}))
