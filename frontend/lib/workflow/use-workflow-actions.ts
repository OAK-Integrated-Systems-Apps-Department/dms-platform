"use client"

import { useMutation, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import {
  addSupportingDocument,
  approveTask,
  cancelWorkflowInstance,
  claimTask,
  declineInfoRequest,
  delegateTask,
  raiseInfoRequest,
  reassignInfoRequest,
  reassignTask,
  rejectTask,
  releaseTask,
  removeSupportingDocument,
  respondInfoRequest,
  submitDocumentToWorkflow,
  withdrawInfoRequest,
} from "./api"
import type { SupportingDocumentInput, TaskActionResultDto, WorkflowInstanceDto } from "./api"
import { INSTANCE_STATUS_LABELS } from "./workflow-badges"
import { workflowErrorMessage } from "./workflow-errors"

/**
 * Every runtime workflow action - submit, cancel, claim, release, approve,
 * reject, and the Phase 2 ones (delegate, reassign, information requests,
 * supporting documents). Any of them can change the queue, the badge count, an instance's
 * status and a document's history at once, so each success simply
 * invalidates everything under ["workflow"].
 *
 * Each action resolves to its result, or `null` when it failed (already
 * toasted with the friendly message for the backend's error code).
 */
export function useWorkflowActions() {
  const queryClient = useQueryClient()

  function invalidate() {
    queryClient.invalidateQueries({ queryKey: ["workflow"] })
  }

  const mutation = useMutation({
    mutationFn: async ({ run }: { run: () => Promise<unknown>; failure: string }) => run(),
    onSuccess: invalidate,
    onError: (error, { failure }) => {
      // The task may have moved on (claimed by someone else, stage closed) -
      // refresh so the stale row disappears.
      invalidate()
      toast.error(workflowErrorMessage(error, failure))
    },
  })

  async function run<T>(fn: () => Promise<T>, failure: string): Promise<T | null> {
    try {
      return (await mutation.mutateAsync({ run: fn, failure })) as T
    } catch {
      return null
    }
  }

  function toastDecision(result: TaskActionResultDto | null, verb: string) {
    if (!result) return
    if (result.instanceStatus !== "in_progress") {
      toast.success(`Task ${verb}. The workflow is now ${INSTANCE_STATUS_LABELS[result.instanceStatus].toLowerCase()}.`)
    } else {
      toast.success(`Task ${verb}.`)
    }
  }

  return {
    isActing: mutation.isPending,
    submit: async (
      documentId: string,
      templateId?: string,
      supportingDocuments?: SupportingDocumentInput[]
    ): Promise<WorkflowInstanceDto | null> => {
      const instance = await run(
        () => submitDocumentToWorkflow(documentId, templateId, supportingDocuments),
        "Could not submit this document for approval."
      )
      if (instance) toast.success("Submitted for approval.")
      return instance
    },
    cancel: async (instanceId: string, reason: string): Promise<WorkflowInstanceDto | null> => {
      const instance = await run(() => cancelWorkflowInstance(instanceId, reason), "Could not cancel this workflow.")
      if (instance) toast.success("Workflow cancelled.")
      return instance
    },
    claim: async (taskId: string) => {
      const result = await run(() => claimTask(taskId), "Could not claim this task.")
      if (result) toast.success("Task claimed. It's now assigned to you.")
      return result
    },
    release: async (taskId: string) => {
      const result = await run(() => releaseTask(taskId), "Could not release this task.")
      if (result) toast.success("Task released back to the pool.")
      return result
    },
    approve: async (taskId: string, comment?: string) => {
      const result = await run(() => approveTask(taskId, comment), "Could not approve this task.")
      toastDecision(result, "approved")
      return result
    },
    reject: async (taskId: string, reason: string) => {
      const result = await run(() => rejectTask(taskId, reason), "Could not reject this task.")
      toastDecision(result, "rejected")
      return result
    },
    delegate: async (taskId: string, delegateUserId: string, note?: string) => {
      const result = await run(() => delegateTask(taskId, delegateUserId, note), "Could not delegate this task.")
      if (result) toast.success("Task delegated. You'll no longer see it in your queue.")
      return result
    },
    reassign: async (taskId: string, userId: string, reason: string) => {
      const result = await run(() => reassignTask(taskId, userId, reason), "Could not reassign this task.")
      if (result) toast.success("Task reassigned.")
      return result
    },
    requestInfo: async (taskId: string, requestedFromUserId: string, message: string, dueAt?: string | null) => {
      const result = await run(() => raiseInfoRequest(taskId, requestedFromUserId, message, dueAt), "Could not send the request.")
      if (result) toast.success("Request sent. Your task is paused until it's answered.")
      return result
    },
    respondInfo: async (infoRequestId: string, message: string | null, documents: SupportingDocumentInput[]) => {
      const result = await run(() => respondInfoRequest(infoRequestId, message, documents), "Could not send your answer.")
      if (result) toast.success("Answer sent.")
      return result
    },
    declineInfo: async (infoRequestId: string, reason: string) => {
      const result = await run(() => declineInfoRequest(infoRequestId, reason), "Could not decline the request.")
      if (result) toast.success("Request declined.")
      return result
    },
    withdrawInfo: async (infoRequestId: string) => {
      const result = await run(() => withdrawInfoRequest(infoRequestId), "Could not withdraw the request.")
      if (result) toast.success("Request withdrawn.")
      return result
    },
    redirectInfo: async (infoRequestId: string, requestedFromUserId: string, reason?: string) => {
      const result = await run(() => reassignInfoRequest(infoRequestId, requestedFromUserId, reason), "Could not redirect the request.")
      if (result) toast.success("Request redirected.")
      return result
    },
    attachDocument: async (instanceId: string, input: SupportingDocumentInput) => {
      const result = await run(() => addSupportingDocument(instanceId, input), "Could not attach the document.")
      if (result) toast.success("Supporting document attached.")
      return result
    },
    removeDocument: async (instanceId: string, supportingDocumentId: string) => {
      const result = await run(
        async () => {
          await removeSupportingDocument(instanceId, supportingDocumentId)
          return true
        },
        "Could not remove the document."
      )
      if (result) toast.success("Supporting document removed.")
      return result
    },
  }
}
