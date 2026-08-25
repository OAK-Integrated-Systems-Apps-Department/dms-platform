"use client"

import { useQuery } from "@tanstack/react-query"

import { API_BASE_URL } from "@/lib/api-config"

/** Mirrors backend/dms-core's UserResponse DTO (only the fields the UI uses). */
export interface CurrentUser {
  userId: string
  email: string
  displayName: string
  status: string
}

function initialsOf(displayName: string): string {
  const parts = displayName.trim().split(/\s+/)
  const first = parts[0]?.[0] ?? ""
  const last = parts.length > 1 ? (parts[parts.length - 1]?.[0] ?? "") : ""
  return (first + last).toUpperCase() || "?"
}

async function fetchCurrentUser(): Promise<CurrentUser> {
  const response = await fetch(`${API_BASE_URL}/api/v1/users/me`, {
    credentials: "include",
  })
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    throw new Error(body?.message ?? "Could not load your profile.")
  }
  return response.json()
}

/**
 * Loads the real signed-in user from dms-core (GET /api/v1/users/me),
 * replacing the mock `currentUser` in components/dashboard/data.ts.
 * `initials` is derived client-side since the backend doesn't return one.
 */
export function useCurrentUser() {
  const query = useQuery({
    queryKey: ["current-user"],
    queryFn: fetchCurrentUser,
    retry: false,
  })

  return {
    ...query,
    initials: query.data ? initialsOf(query.data.displayName) : undefined,
  }
}