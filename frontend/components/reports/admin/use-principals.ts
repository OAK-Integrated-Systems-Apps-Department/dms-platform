"use client"

import { keepPreviousData, useQuery } from "@tanstack/react-query"

import { API_BASE_URL } from "@/lib/api-config"
import { apiFetchJson } from "@/lib/api-fetch"
import { fetchAllDepartments } from "@/lib/departments/api"

export interface PrincipalOption {
  value: string
  label: string
}

/** Names seen so far, so a chosen person or role keeps its name after a later search no longer lists them. */
const known = new Map<string, string>()
export const knownName = (id: string): string | undefined => known.get(id)

function usePrincipalSearch(kind: "user" | "role", q: string, enabled: boolean) {
  const term = q.trim()
  return useQuery({
    queryKey: ["reports", "principals", kind, term],
    enabled,
    staleTime: 60_000,
    placeholderData: keepPreviousData,
    queryFn: async (): Promise<PrincipalOption[]> => {
      const rows = await apiFetchJson<{ value: string; label: string }[]>(
        `${API_BASE_URL}/api/v1/reports/admin/principals?type=${kind}&limit=100&q=${encodeURIComponent(term)}`,
        undefined,
        kind === "user" ? "Could not load people." : "Could not load roles."
      )
      rows.forEach((r) => known.set(r.value, r.label))
      return rows
    },
  })
}

/** Active people matching `q` (all of them up to 100 when empty), searched on the server. */
export const useUserOptions = (enabled = true, q = "") => usePrincipalSearch("user", q, enabled)
export const useRoleOptions = (enabled = true, q = "") => usePrincipalSearch("role", q, enabled)

export function useDepartmentOptions(enabled = true) {
  return useQuery({
    queryKey: ["reports", "principals", "departments"],
    enabled,
    staleTime: 60_000,
    queryFn: async (): Promise<PrincipalOption[]> => {
      const all = await fetchAllDepartments("active")
      all.forEach((d) => known.set(d.departmentId, d.name))
      return all.map((d) => ({ value: d.departmentId, label: d.name }))
    },
  })
}
