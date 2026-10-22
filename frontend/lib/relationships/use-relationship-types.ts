"use client"

import { useQuery } from "@tanstack/react-query"

import { ApiError } from "@/lib/api-error"
import { fetchRelationshipTypes } from "@/lib/relationships/api"

/** A 403 is a settled fact about this caller - don't retry it. */
function retryUnlessForbidden(failureCount: number, failure: unknown) {
  return !(failure instanceof ApiError && (failure.status === 403 || failure.status === 404)) && failureCount < 3
}

/** Enabled types - what the add-link dialogs offer. */
export function useEnabledRelationshipTypes(enabled = true) {
  return useQuery({
    queryKey: ["relationship-types", "enabled"],
    queryFn: () => fetchRelationshipTypes(false),
    enabled,
    staleTime: 30_000,
    retry: retryUnlessForbidden,
  })
}

/** Every type with usage counts - the admin screen. */
export function useAllRelationshipTypes(enabled = true) {
  return useQuery({
    queryKey: ["relationship-types", "all"],
    queryFn: () => fetchRelationshipTypes(true),
    enabled,
    retry: retryUnlessForbidden,
  })
}
