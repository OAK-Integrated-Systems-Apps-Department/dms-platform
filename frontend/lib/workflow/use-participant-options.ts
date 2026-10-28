"use client"

import { useQuery } from "@tanstack/react-query"

import { fetchActiveDepartmentOptions } from "@/lib/departments/api"

import { fetchDocumentTypeOptions, fetchRoleOptions, fetchUserOptions } from "./api"

const LOOKUP_STALE_MS = 5 * 60 * 1000

/**
 * The three option lists a stage participant can be picked from. Each is
 * fetched lazily (only once its tab is opened) and cached for five minutes.
 * All come from endpoints a template designer can read
 * (/api/v1/workflow/lookups/* and /api/v1/departments).
 */
export function useUserOptions(enabled = true) {
  return useQuery({
    queryKey: ["lookup", "users"],
    queryFn: fetchUserOptions,
    enabled,
    staleTime: LOOKUP_STALE_MS,
    retry: false,
  })
}

export function useRoleOptions(enabled = true) {
  return useQuery({
    queryKey: ["lookup", "roles"],
    queryFn: fetchRoleOptions,
    enabled,
    staleTime: LOOKUP_STALE_MS,
    retry: false,
  })
}

export function useDepartmentOptions(enabled = true) {
  return useQuery({
    queryKey: ["lookup", "departments"],
    queryFn: fetchActiveDepartmentOptions,
    enabled,
    staleTime: LOOKUP_STALE_MS,
    retry: false,
  })
}

/** Active document types, for the template form and the template list filter. */
export function useDocumentTypeOptions(enabled = true) {
  return useQuery({
    queryKey: ["lookup", "document-types"],
    queryFn: fetchDocumentTypeOptions,
    enabled,
    staleTime: LOOKUP_STALE_MS,
    retry: false,
  })
}
