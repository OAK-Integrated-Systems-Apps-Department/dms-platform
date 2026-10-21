"use client"

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import {
  fetchDocumentSignatures,
  fetchMyVisualSignature,
  releaseSignatureLock,
  revokeSigningKey,
  saveMyVisualSignature,
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

/** The caller's own saved visual signature (null if they've never set one) - used to pre-fill the sign-and-approve dialog. */
export function useMyVisualSignature() {
  return useQuery({
    queryKey: ["visual-signature", "me"],
    queryFn: fetchMyVisualSignature,
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
    onSuccess: () => {
      invalidate()
      // Signing saves whatever was typed/drawn as the new default (see DocumentSignatureService#sign).
      queryClient.invalidateQueries({ queryKey: ["visual-signature", "me"] })
    },
  })

  // Deliberately no onError toast here, unlike the other mutations below: VisualSignatureCard needs
  // the raw error (via the thrown rejection `saveVisualSignature` below passes through) to show a
  // step-up failure inline in ConfirmPasswordDialog and keep it open, the same way
  // SignAndApproveDialog handles its own password step - a background toast plus a closed dialog
  // would lose the password-retry flow entirely.
  const saveVisualSignature = useMutation({
    mutationFn: saveMyVisualSignature,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["visual-signature", "me"] }),
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
    /** Rethrows on failure (e.g. wrong password) - see the mutation's own comment on why this doesn't swallow it. */
    saveVisualSignature: (input: Parameters<typeof saveMyVisualSignature>[0]) => saveVisualSignature.mutateAsync(input),
    isSavingVisualSignature: saveVisualSignature.isPending,
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
