"use client"

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"

import {
  cancelBulkImport,
  createBulkImport,
  fetchActiveBulkImport,
  fetchBulkImportStatus,
  isInFlight,
  submitBulkImport,
  type BulkImportCreatedResponseDto,
  type DuplicateAction,
} from "./api"

export const BULK_IMPORTS_KEY = ["bulk-imports"] as const
const ACTIVE_KEY = [...BULK_IMPORTS_KEY, "active"] as const
/** How often a submitted batch is re-read while the worker processes it. */
const POLL_INTERVAL_MS = 3000

function statusKey(batchId: string) {
  return [...BULK_IMPORTS_KEY, batchId] as const
}

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback
}

/**
 * The caller's in-progress bulk import (the one blocking a new import),
 * plus the status of the batch currently being worked on - `batchId`, or
 * else the active batch if it's already submitted, so a queued/running
 * import found on load is watched too (`watchedId`). A submitted batch is
 * polled until it finishes; the polling stops on unmount. Mutations
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

  const active = activeQuery.data ?? null
  const watchedId =
    batchId ?? (active && isInFlight(active.status) ? active.batchId : null)

  const statusQuery = useQuery({
    queryKey: statusKey(watchedId ?? ""),
    queryFn: () => fetchBulkImportStatus(watchedId as string),
    retry: false,
    enabled: enabled && Boolean(watchedId),
    refetchInterval: (query) =>
      isInFlight(query.state.data?.status) ? POLL_INTERVAL_MS : false,
  })

  function invalidate() {
    return queryClient.invalidateQueries({ queryKey: BULK_IMPORTS_KEY })
  }

  const createMutation = useMutation({
    mutationFn: ({
      template,
      folderId,
      duplicateAction,
      ocrFields,
    }: {
      template: File
      folderId?: string | null
      duplicateAction?: DuplicateAction
      ocrFields?: readonly string[]
    }) => createBulkImport(template, folderId, duplicateAction, ocrFields),
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
    active,
    watchedId,
    activeLoading: activeQuery.isLoading,
    activeError: activeQuery.error,
    status: statusQuery.data ?? null,
    statusLoading: statusQuery.isLoading,
    refetchStatus: () => statusQuery.refetch(),
    /**
     * Forget the active batch (it has finished, so it no longer blocks a
     * new import) and re-check with the server.
     */
    clearActive: () => {
      queryClient.setQueryData(ACTIVE_KEY, null)
      return invalidate()
    },
    creating: createMutation.isPending,
    submitting: submitMutation.isPending,
    cancelling: cancelMutation.isPending,
    create: async (
      template: File,
      folderId?: string | null,
      duplicateAction?: DuplicateAction,
      ocrFields?: readonly string[]
    ): Promise<
      | { batch: BulkImportCreatedResponseDto; error: null }
      | { batch: null; error: string }
    > => {
      try {
        return {
          batch: await createMutation.mutateAsync({
            template,
            folderId,
            duplicateAction,
            ocrFields,
          }),
          error: null,
        }
      } catch (err) {
        return {
          batch: null,
          error: errorMessage(err, "Could not upload the template."),
        }
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
