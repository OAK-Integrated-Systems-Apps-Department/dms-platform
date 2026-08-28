import { create } from "zustand"

import { initialUsers } from "@/components/users/data"
import type { UserRecord } from "@/components/users/types"

interface UsersState {
  users: UserRecord[]
  toggleStatus: (id: string) => void
}

/**
 * In-memory user store — swap for real API calls (React Query + your
 * backend) once the User Management endpoints exist. Same pattern as
 * stores/roles-store.ts, kept as its own store rather than merged into it
 * since users and roles are separate resources with separate lifecycles.
 */
export const useUsersStore = create<UsersState>((set) => ({
  users: initialUsers,

  toggleStatus: (id) => {
    set((state) => ({
      users: state.users.map((user) =>
        user.id === id ? { ...user, status: user.status === "active" ? "inactive" : "active" } : user
      ),
    }))
  },
}))