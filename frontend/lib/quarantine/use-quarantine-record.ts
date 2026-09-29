"use client"

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import {
  deleteQuarantineRecord,
  fetchQuarantineRecord,
  overrideReleaseQuarantineRecord,
  releaseQuarantineRecord,
} from "./api"
import type { QuarantineReleaseResultDto } from "./api"

/**
 * One quarantined file plus its review actions, in the `lib/metadata-fields`
 * hook style: mutations invalidate the cached record/list and toast the
 * outcome. While a re-scan release is still running (a cloud scanner's
 * verdict arrives asynchronously) the record re-fetches every 5 seconds
 * until the attempt settles.
 */
export function useQuarantineRecord(id: string) {
  const queryClient = useQueryClient()

  const query = useQuery({
    queryKey: ["quarantine", "detail", id],
    queryFn: () => fetchQuarantineRecord(id),
    enabled: Boolean(id),
    refetchInterval: (q) => (q.state.data?.releaseInProgress ? 5000 : false),
  })

  function invalidate() {
    queryClient.invalidateQueries({ queryKey: ["quarantine"] })
  }

  function toastRelease(result: QuarantineReleaseResultDto) {
    if (result.outcome === "released") toast.success(result.message)
    else if (result.outcome === "pending") toast.info(result.message)
    else toast.error(result.message)
  }

  const releaseMutation = useMutation({
    mutationFn: (reason: string) => releaseQuarantineRecord(id, reason),
    onSuccess: (result) => {
      invalidate()
      toastRelease(result)
    },
    onError: (error) => toast.error(error instanceof Error ? error.message : "Could not release this file."),
  })

  const overrideMutation = useMutation({
    mutationFn: (reason: string) => overrideReleaseQuarantineRecord(id, reason),
    onSuccess: (result) => {
      invalidate()
      toastRelease(result)
    },
    onError: (error) => toast.error(error instanceof Error ? error.message : "Could not override-release this file."),
  })

  const deleteMutation = useMutation({
    mutationFn: (reason: string) => deleteQuarantineRecord(id, reason),
    onSuccess: () => {
      invalidate()
      toast.success("The quarantined file was permanently deleted.")
    },
    onError: (error) => toast.error(error instanceof Error ? error.message : "Could not delete this file."),
  })

  return {
    ...query,
    release: releaseMutation.mutate,
    overrideRelease: overrideMutation.mutate,
    remove: deleteMutation.mutate,
    isActing: releaseMutation.isPending || overrideMutation.isPending || deleteMutation.isPending,
  }
}
