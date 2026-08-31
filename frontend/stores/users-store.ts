import { create } from "zustand"

import { initialUsers } from "@/components/users/data"
import type { UserRecord } from "@/components/users/types"

interface UsersState {
  users: UserRecord[]
  toggleStatus: (id: string) => void
}

/**
 * In-memory user store — swap `initialUsers` / `toggleStatus` for real API
 * calls (React Query + `PUT /admin/realms/{realm}/users/{id}`, body
 * `{ enabled }`) once the User Management endpoints exist. Mirrors
 * `stores/roles-store.ts`'s shape; zustand is already a project dependency.
 */
export const useUsersStore = create<UsersState>((set) => ({
  users: initialUsers,

  toggleStatus: (id) => {
    set((state) => ({
      users: state.users.map((user) =>
        user.id === id
          ? {
              ...user,
              status: user.status === "Active" ? "Inactive" : "Active",
            }
          : user
      ),
    }))
  },
}))
