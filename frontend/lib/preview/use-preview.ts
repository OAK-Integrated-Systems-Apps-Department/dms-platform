"use client"

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import {
  fetchPreviewFailures,
  fetchPreviewPolicy,
  retryPreviewFailure,
  savePreviewPolicy,
  type PreviewPolicyDto,
} from "./api"

export const POLICY_KEY = ["preview", "policy"] as const
const FAILURES_KEY = ["preview", "failures"] as const

/**
 * What the caller's organisation allows from a preview. Fetched fresh whenever a preview opens (a minute of
 * freshness at most): a restriction an administrator just added must not wait for a page reload.
 */
export function usePreviewPolicy(enabled = true) {
  return useQuery({
    queryKey: POLICY_KEY,
    queryFn: fetchPreviewPolicy,
    enabled,
    staleTime: 60_000,
    retry: false,
  })
}

export function useSavePreviewPolicy() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (policy: PreviewPolicyDto) => savePreviewPolicy(policy),
    onSuccess: (saved) => {
      queryClient.setQueryData(POLICY_KEY, saved)
      toast.success("Preview settings saved.")
    },
    onError: (error) =>
      toast.error(
        error instanceof Error && error.message
          ? error.message
          : "Could not save the preview settings."
      ),
  })
}

/** Previews that could not be made, for the administrator - one page at a time. */
export function usePreviewFailures(page: number, size = 10) {
  return useQuery({
    queryKey: [...FAILURES_KEY, page, size],
    queryFn: () => fetchPreviewFailures(page, size),
    retry: false,
  })
}

export function useRetryPreview() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (versionId: string) => retryPreviewFailure(versionId),
    onSuccess: () => {
      toast.success("Trying again in the background.")
      void queryClient.invalidateQueries({ queryKey: FAILURES_KEY })
    },
    onError: (error) =>
      toast.error(
        error instanceof Error && error.message
          ? error.message
          : "Could not retry that preview."
      ),
  })
}
