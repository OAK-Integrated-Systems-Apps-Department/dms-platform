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
 */
export function useDashboard(days: DashboardPeriod = 30) {
  return useQuery({
    queryKey: [...DASHBOARD_QUERY_KEY, days],
    queryFn: () => fetchDashboard(days),
    staleTime: 60_000,
    placeholderData: keepPreviousData,
  })
}
