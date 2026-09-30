"use client"

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"

import {
  cancelBulkImport,
  createBulkImport,
  fetchActiveBulkImport,
  fetchBulkImportStatus,
  submitBulkImport,
  type BulkImportCreatedResponseDto,
} from "./api"

export const BULK_IMPORTS_KEY = ["bulk-imports"] as const
const ACTIVE_KEY = [...BULK_IMPORTS_KEY, "active"] as const

function statusKey(batchId: string) {
  return [...BULK_IMPORTS_KEY, batchId] as const
}

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback
}

/**
 * The caller's in-progress bulk import (the one blocking a new import),
 * plus the status of the batch currently being worked on. Mutations
 * resolve to an error message, or `null` on success - same convention as
 * `useDocumentType`.
 */
export function useBulkImport(batchId: string | null, enabled = true) {
  const queryClient = useQueryClient()

  const activeQuery = useQuery({
    queryKey: ACTIVE_KEY,
    queryFn: fetchActiveBulkImport,
    retry: false,
    enabled,
  })

  const statusQuery = useQuery({
    queryKey: statusKey(batchId ?? ""),
    queryFn: () => fetchBulkImportStatus(batchId as string),
    retry: false,
    enabled: enabled && Boolean(batchId),
  })

  function invalidate() {
    return queryClient.invalidateQueries({ queryKey: BULK_IMPORTS_KEY })
  }

  const createMutation = useMutation({
    mutationFn: (template: File) => createBulkImport(template),
    onSuccess: () => invalidate(),
  })
  const submitMutation = useMutation({
    mutationFn: (id: string) => submitBulkImport(id),
    onSuccess: () => invalidate(),
  })
  const cancelMutation = useMutation({
    mutationFn: (id: string) => cancelBulkImport(id),
    // A cancelled batch no longer blocks a new import - clear it now so the
    // screen doesn't flash the stale resume card while the refetch runs.
    onSuccess: () => {
      queryClient.setQueryData(ACTIVE_KEY, null)
      return invalidate()
    },
  })

  return {
    active: activeQuery.data ?? null,
    activeLoading: activeQuery.isLoading,
    activeError: activeQuery.error,
    status: statusQuery.data ?? null,
    statusLoading: statusQuery.isLoading,
    refetchStatus: () => statusQuery.refetch(),
    creating: createMutation.isPending,
    submitting: submitMutation.isPending,
    cancelling: cancelMutation.isPending,
    create: async (
      template: File
    ): Promise<{ batch: BulkImportCreatedResponseDto; error: null } | { batch: null; error: string }> => {
      try {
        return { batch: await createMutation.mutateAsync(template), error: null }
      } catch (err) {
        return { batch: null, error: errorMessage(err, "Could not upload the template.") }
      }
    },
    submit: async (id: string): Promise<string | null> => {
      try {
        await submitMutation.mutateAsync(id)
        return null
      } catch (err) {
        return errorMessage(err, "Could not submit this import.")
      }
    },
    cancel: async (id: string): Promise<string | null> => {
      try {
        await cancelMutation.mutateAsync(id)
        return null
      } catch (err) {
        return errorMessage(err, "Could not cancel this import.")
      }
    },
  }
}
