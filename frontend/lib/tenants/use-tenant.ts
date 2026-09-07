"use client"

import { useQuery } from "@tanstack/react-query"

import { fetchTenant } from "@/lib/tenants/api"

/**
 * Loads a single tenant by id (`GET /api/v1/tenants/{tenantId}`) so screens
 * that only have a `tenantId` — like My Profile via `useCurrentUser` — can
 * display the tenant's name instead of its raw id.
 */
export function useTenant(tenantId: string | undefined) {
  return useQuery({
    queryKey: ["tenant", tenantId],
    queryFn: () => fetchTenant(tenantId as string),
    enabled: Boolean(tenantId),
    retry: false,
    staleTime: 5 * 60 * 1000,
  })
}