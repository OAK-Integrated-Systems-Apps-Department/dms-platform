import { create } from "zustand"

import { initialRoles } from "@/components/roles/data"
import type { RoleFormValues } from "@/components/roles/schema"
import type { RoleRecord } from "@/components/roles/types"

interface RolesState {
  roles: RoleRecord[]
  addRole: (values: RoleFormValues) => RoleRecord
  updateRole: (id: string, values: RoleFormValues) => void
  updateRolePermissions: (id: string, permissions: string[]) => void
  toggleStatus: (id: string) => void
}

/**
 * In-memory role store — swap the initial state / actions for real API
 * calls (React Query + your backend) once the Role Management endpoints
 * exist. Using zustand here since it's already a project dependency.
 */
export const useRolesStore = create<RolesState>((set) => ({
  roles: initialRoles,

  addRole: (values) => {
    const role: RoleRecord = {
      id: `role-${Date.now()}`,
      name: values.name,
      description: values.description ?? "",
      status: values.status,
      assignedUsers: 0,
      permissions: values.permissions,
    }
    set((state) => ({ roles: [role, ...state.roles] }))
    return role
  },

  updateRole: (id, values) => {
    set((state) => ({
      roles: state.roles.map((role) =>
        role.id === id
          ? {
              ...role,
              name: values.name,
              description: values.description ?? "",
              status: values.status,
              permissions: values.permissions,
            }
          : role
      ),
    }))
  },

  /** Used by the standalone Role Permission Configuration screen — leaves name/description/status untouched. */
  updateRolePermissions: (id, permissions) => {
    set((state) => ({
      roles: state.roles.map((role) =>
        role.id === id ? { ...role, permissions } : role
      ),
    }))
  },

  toggleStatus: (id) => {
    set((state) => ({
      roles: state.roles.map((role) =>
        role.id === id
          ? {
              ...role,
              status: role.status === "Active" ? "Inactive" : "Active",
            }
          : role
      ),
    }))
  },
}))
