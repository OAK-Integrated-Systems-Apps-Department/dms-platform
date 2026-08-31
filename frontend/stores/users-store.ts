import { create } from "zustand"

import { initialUsers } from "@/components/users/data"

import type { UserRecord } from "@/components/users/types"

interface UsersState {
  users: UserRecord[]

  toggleStatus: (id: string) => void

  sendPasswordReset: (id: string) => void

  requireMfaSetup: (id: string) => void

  resetMfaCredentials: (id: string) => void

  revokeSession: (id: string, sessionId: string) => void

  revokeAllSessions: (id: string) => void

  updateUser: (
    id: string,
    updates: {
      firstName: string
      lastName: string
      email: string
      additionalProfile?: string
    }
  ) => void
}

/**
 * In-memory user store — swap `initialUsers` / `toggleStatus` /
 * `sendPasswordReset` / `requireMfaSetup` / `resetMfaCredentials` /
 * `revokeSession` / `revokeAllSessions` for real API calls (React Query +
 * Keycloak admin endpoints) once the User Management endpoints exist.
 *
 * Mirrors `stores/roles-store.ts`'s shape; zustand is already a project
 * dependency.
 *
 * Kept as its own store rather than merged into the roles store since users
 * and roles are separate resources with separate lifecycles.
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

  sendPasswordReset: (id) => {
    // Placeholder for `PUT /admin/realms/{realm}/users/{id}/reset-password`
    // or `.../execute-actions-email` (U7). Fire-and-forget from DMS's side
    // — Keycloak owns the credential, so there's no local field to update
    // here the way `toggleStatus` updates `status`.
    console.info(`Password reset requested for user ${id}`)
  },

  requireMfaSetup: (id) => {
    // Placeholder for `PUT /admin/realms/{realm}/users/{id}` body
    // `{ requiredActions: ["CONFIGURE_TOTP"] }` (U8). Doesn't change
    // enrollment state today — it takes effect on the user's next
    // sign-in — so there's no local field to flip yet, same reasoning as
    // `sendPasswordReset`.
    console.info(`MFA setup required for user ${id}`)
  },

  resetMfaCredentials: (id) => {
    // `DELETE /admin/realms/{realm}/users/{id}/credentials/{credentialId}`
    // (U8). Unlike the two placeholders above, this one has an immediate,
    // locally-visible effect, so it's modeled as a real state update
    // (mirrors `toggleStatus`) rather than a no-op.
    set((state) => ({
      users: state.users.map((user) =>
        user.id === id
          ? {
              ...user,
              mfaEnrolled: false,
              mfaMethods: [],
              mfaEnrolledOn: null,
            }
          : user
      ),
    }))
  },

  revokeSession: (id, sessionId) => {
    // `DELETE /admin/realms/{realm}/sessions/{sessionId}` (U9). Keeps
    // `activeSessions` (the plain count View User shows) in sync with
    // `sessions.length` (the detailed list U9 shows) so the two screens
    // never disagree.
    set((state) => ({
      users: state.users.map((user) =>
        user.id === id
          ? {
              ...user,
              sessions: user.sessions.filter(
                (session) => session.id !== sessionId
              ),
              activeSessions: Math.max(0, user.activeSessions - 1),
            }
          : user
      ),
    }))
  },

  revokeAllSessions: (id) => {
    // `POST /admin/realms/{realm}/users/{id}/logout` (U9).
    set((state) => ({
      users: state.users.map((user) =>
        user.id === id
          ? {
              ...user,
              sessions: [],
              activeSessions: 0,
            }
          : user
      ),
    }))
  },

  updateUser: (id, updates) => {
    set((state) => ({
      users: state.users.map((user) =>
        user.id === id
          ? {
              ...user,
              firstName: updates.firstName,
              lastName: updates.lastName,
              name: `${updates.firstName} ${updates.lastName}`.trim(),
              email: updates.email,
            }
          : user
      ),
    }))
  },
}))
