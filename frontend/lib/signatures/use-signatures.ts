"use client"

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import {
  fetchDocumentSignatures,
  releaseSignatureLock,
  revokeSigningKey,
  signAndApproveTask,
  verifyDocumentSignatures,
  type SignAndApproveRequestDto,
} from "./api"
import { friendlyMessage } from "@/lib/api-error"

/** A document's signatures - refetched after any workflow action or signature mutation (see the invalidations below). */
export function useDocumentSignatures(documentId: string | null) {
  return useQuery({
    queryKey: ["signatures", documentId],
    queryFn: () => fetchDocumentSignatures(documentId as string),
    enabled: Boolean(documentId),
  })
}

/**
 * Mutations that change signature or workflow state - each invalidates both
 * `["workflow"]` (a sign-and-approve also decides a task) and `["signatures"]`.
 * `signAndApprove` deliberately does NOT swallow its error the way
 * `useWorkflowActions` does: `SignAndApproveDialog` needs the raw failure to
 * decide whether to keep itself open and which field to blame (step-up vs.
 * something else) - see that component's own doc comment.
 */
export function useSignatureActions() {
  const queryClient = useQueryClient()

  function invalidate() {
    queryClient.invalidateQueries({ queryKey: ["workflow"] })
    queryClient.invalidateQueries({ queryKey: ["signatures"] })
  }

  const signAndApprove = useMutation({
    mutationFn: ({ taskId, request }: { taskId: string; request: SignAndApproveRequestDto }) =>
      signAndApproveTask(taskId, request),
    onSuccess: invalidate,
  })

  const verifyAll = useMutation({
    mutationFn: (documentId: string) => verifyDocumentSignatures(documentId),
    onSuccess: invalidate,
    onError: (error) => toast.error(friendlyMessage(error, "Could not verify this document's signatures.")),
  })

  const unlock = useMutation({
    mutationFn: ({ documentId, reason }: { documentId: string; reason: string }) =>
      releaseSignatureLock(documentId, reason),
    onSuccess: () => {
      invalidate()
      toast.success("Document unlocked.")
    },
    onError: (error) => toast.error(friendlyMessage(error, "Could not unlock this document.")),
  })

  const revokeKey = useMutation({
    mutationFn: ({ userId, reason }: { userId: string; reason: string }) => revokeSigningKey(userId, reason),
    onSuccess: () => {
      invalidate()
      toast.success("Signing key revoked.")
    },
    onError: (error) => toast.error(friendlyMessage(error, "Could not revoke this signing key.")),
  })

  return {
    signAndApprove,
    verifyAll: async (documentId: string) => {
      try {
        return await verifyAll.mutateAsync(documentId)
      } catch {
        return null
      }
    },
    isVerifying: verifyAll.isPending,
    unlock: async (documentId: string, reason: string) => {
      try {
        await unlock.mutateAsync({ documentId, reason })
        return true
      } catch {
        return false
      }
    },
    isUnlocking: unlock.isPending,
    revokeKey: async (userId: string, reason: string) => {
      try {
        await revokeKey.mutateAsync({ userId, reason })
        return true
      } catch {
        return false
      }
    },
  }
}
