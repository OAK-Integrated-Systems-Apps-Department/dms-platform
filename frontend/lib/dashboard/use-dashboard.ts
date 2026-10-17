"use client"

import { keepPreviousData, useQuery } from "@tanstack/react-query"

import { fetchDashboard, type DashboardPeriod } from "@/lib/dashboard/api"

/** The query key every view of the dashboard figures starts with - invalidate it after uploads and changes. */
export const DASHBOARD_QUERY_KEY = ["dashboard"] as const

/**
 * The caller's dashboard figures (GET /api/v1/dashboard) for a period,
 * shared by the home page and the top bar's recent-documents and activity
 * menus - one request serves both. Switching period keeps showing the last
 * figures until the new ones arrive.
 *
 * `enabled` exists for one case: this endpoint is tenant-scoped, and a
 * platform administrator belongs to no tenant, so for them it can only ever
 * answer 401 TENANT_CONTEXT_MISSING. Callers that know the account has no
 * tenant - or do not know yet - pass false and the request is never made.
 *
 * Note that enabling is a union across observers: this query runs if ANY
 * mounted caller enables it. Both callers (the home page and the top bar)
 * therefore have to gate it, or the one that does not re-enables it for the
 * one that does.
 */
export function useDashboard(days: DashboardPeriod = 30, enabled = true) {
  return useQuery({
    queryKey: [...DASHBOARD_QUERY_KEY, days],
    queryFn: () => fetchDashboard(days),
    enabled,
    staleTime: 60_000,
    // Uploads, deletes and restores happen on other pages - always refresh on arrival.
    refetchOnMount: "always",
    placeholderData: keepPreviousData,
  })
}
