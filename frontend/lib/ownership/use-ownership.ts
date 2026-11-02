"use client"

import { keepPreviousData, useQuery } from "@tanstack/react-query"

import { ApiError } from "@/lib/api-error"
import {
  fetchDocumentOwnership,
  fetchOffboardingPreview,
  fetchOwnedDocuments,
  fetchOwnershipHistory,
  fetchPendingDocuments,
} from "@/lib/ownership/api"

import type { OwnedDocumentsFilters } from "@/lib/ownership/types"

/** A 403/404 is a settled fact about this caller - don't retry it. */
function retryUnlessSettled(failureCount: number, failure: unknown) {
  return !(failure instanceof ApiError && (failure.status === 403 || failure.status === 404)) && failureCount < 3
}

export function useDocumentOwnership(documentId: string) {
  return useQuery({
    queryKey: ["ownership", "document", documentId],
    queryFn: () => fetchDocumentOwnership(documentId),
    retry: retryUnlessSettled,
  })
}

/** Needs a user or department chosen first. */
export function useOwnedDocuments(filters: OwnedDocumentsFilters, page: number, size: number) {
  return useQuery({
    queryKey: ["ownership", "owned", filters, page, size],
    queryFn: () => fetchOwnedDocuments(filters, page, size),
    enabled: Boolean(filters.userId || filters.departmentId),
    placeholderData: keepPreviousData,
    retry: retryUnlessSettled,
  })
}

export function usePendingDocuments(filters: Pick<OwnedDocumentsFilters, "departmentId" | "q">, page: number, size: number, enabled = true) {
  return useQuery({
    queryKey: ["ownership", "pending", filters, page, size],
    queryFn: () => fetchPendingDocuments(filters, page, size),
    enabled,
    placeholderData: keepPreviousData,
    retry: retryUnlessSettled,
  })
}

export function useOwnershipHistory(documentId: string, page: number, size: number, enabled = true) {
  return useQuery({
    queryKey: ["ownership", "history", documentId, page, size],
    queryFn: () => fetchOwnershipHistory(documentId, page, size),
    enabled,
    placeholderData: keepPreviousData,
    retry: retryUnlessSettled,
  })
}

export function useOffboardingPreview(userId: string, enabled = true) {
  return useQuery({
    queryKey: ["ownership", "offboarding-preview", userId],
    queryFn: () => fetchOffboardingPreview(userId),
    enabled,
    retry: retryUnlessSettled,
    gcTime: 0,
  })
}
