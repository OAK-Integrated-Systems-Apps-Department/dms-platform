"use client"

import { keepPreviousData, useQuery } from "@tanstack/react-query"

import { fetchReviewQueue, fetchSavedReviews } from "@/lib/documents/api"

/**
 * Server-driven pagination, same reasoning as `useAuditEvents`: the queue
 * is a slice of a table that grows with every upload, not a list small
 * enough to fetch whole and page client-side.
 *
 * `page` is 1-indexed (matching `TablePagination`); the backend is
 * 0-indexed, converted here so nothing above this hook needs to know.
 */
export function useReviewQueue(page: number, size: number) {
  return useQuery({
    queryKey: ["review-queue", page, size],
    queryFn: () => fetchReviewQueue(page - 1, size),
    placeholderData: keepPreviousData,
  })
}

/**
 * Documents whose review has already been saved. Same shape and same paging
 * rules as {@link useReviewQueue} - a separate key so the two lists don't
 * share a cache entry and show each other's rows.
 */
export function useSavedReviews(page: number, size: number) {
  return useQuery({
    queryKey: ["saved-reviews", page, size],
    queryFn: () => fetchSavedReviews(page - 1, size),
    placeholderData: keepPreviousData,
  })
}
