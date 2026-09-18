"use client"

import { keepPreviousData, useQuery } from "@tanstack/react-query"

import { fetchAuditEventById, fetchAuditEvents } from "@/lib/audit-events/api"
import type { AuditEventSearchFilters } from "@/lib/audit-events/api"

/**
 * Server-driven - unlike `useUsers`/`useRolesStore` (which fetch an
 * entire tenant's dataset once and filter/sort/paginate client-side, see
 * those files' own doc comments), audit trails are exactly the kind of
 * dataset that pattern doesn't scale to. Every filter/page/size/sort
 * change re-queries the backend, which `GET /api/v1/audit-events` is
 * built for (real query params + real Pageable).
 *
 * `page` is 1-indexed (matching `TablePagination`); the backend is
 * 0-indexed, converted here so nothing above this hook needs to know that.
 */
export function useAuditEvents(
  filters: AuditEventSearchFilters,
  page: number,
  size: number,
  sort: string
) {
  return useQuery({
    queryKey: ["audit-events", filters, page, size, sort],
    queryFn: () => fetchAuditEvents(filters, page - 1, size, sort),
    // Keeps the current page's rows on screen while the next filter/page
    // change is in flight, instead of the table flashing to a loading
    // state on every keystroke/click.
    placeholderData: keepPreviousData,
  })
}

export function useAuditEvent(id: string) {
  return useQuery({
    queryKey: ["audit-events", "detail", id],
    queryFn: () => fetchAuditEventById(id),
    enabled: Boolean(id),
  })
}
