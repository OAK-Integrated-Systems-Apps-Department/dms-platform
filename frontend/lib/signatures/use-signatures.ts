"use client"

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import {
  addDocumentSignaturePage,
  fetchDocumentSignaturePlacementInfo,
  fetchDocumentSignatures,
  fetchMyVisualSignature,
  placeDocumentSignature,
  releaseSignatureLock,
  removeDocumentSignaturePlacement,
  revokeSigningKey,
  saveMyVisualSignature,
  signAndApproveTask,
  signDocumentStandalone,
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

/** Placement info for standalone signing (V118) - mirrors `useDocumentSignatures`, but for the placement/page-count check, not the signature history itself. */
export function useDocumentSignaturePlacementInfo(documentId: string | null) {
  return useQuery({
    queryKey: ["signature-placement-info", "document", documentId],
    queryFn: () => fetchDocumentSignaturePlacementInfo(documentId as string),
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

  function invalidateStandalonePlacement(documentId: string) {
    queryClient.invalidateQueries({ queryKey: ["signature-placement-info", "document", documentId] })
    queryClient.invalidateQueries({ queryKey: ["signatures", documentId] })
    // addPage/place/remove/signStandalone all change the document's currentVersionId - refresh the
    // document itself too, so anything showing it (the detail page, a "View document" preview) picks up
    // the new version rather than reading a stale one from before this action.
    queryClient.invalidateQueries({ queryKey: ["document", documentId] })
  }

  const addStandalonePage = useMutation({
    mutationFn: (documentId: string) => addDocumentSignaturePage(documentId),
    onSuccess: (_, documentId) => invalidateStandalonePlacement(documentId),
  })

  const placeStandalone = useMutation({
    mutationFn: ({ documentId, input }: { documentId: string; input: Parameters<typeof placeDocumentSignature>[1] }) =>
      placeDocumentSignature(documentId, input),
    onSuccess: (_, { documentId }) => invalidateStandalonePlacement(documentId),
  })

  const removeStandalone = useMutation({
    mutationFn: (documentId: string) => removeDocumentSignaturePlacement(documentId),
    onSuccess: (_, documentId) => invalidateStandalonePlacement(documentId),
  })

  // Deliberately no onError toast, same reasoning as signAndApprove: the standalone-sign dialog needs
  // the raw failure to decide whether to stay open and which field to blame (step-up vs. something else).
  const signStandalone = useMutation({
    mutationFn: ({ documentId, request }: { documentId: string; request: Omit<SignAndApproveRequestDto, "meaning"> }) =>
      signDocumentStandalone(documentId, request),
    onSuccess: (_, { documentId }) => {
      invalidateStandalonePlacement(documentId)
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
    addStandalonePage,
    placeStandalone,
    removeStandalone: async (documentId: string) => {
      try {
        await removeStandalone.mutateAsync(documentId)
        return true
      } catch {
        return false
      }
    },
    isRemovingStandalone: removeStandalone.isPending,
    /** Rethrows on failure (e.g. wrong password) - the standalone-sign dialog needs the raw error to decide whether to stay open. */
    signStandalone,
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
