"use client"

import { useMutation, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import {
  approveTask,
  cancelWorkflowInstance,
  claimTask,
  rejectTask,
  releaseTask,
  submitDocumentToWorkflow,
} from "./api"
import type { TaskActionResultDto, WorkflowInstanceDto } from "./api"
import { INSTANCE_STATUS_LABELS } from "./workflow-badges"
import { workflowErrorMessage } from "./workflow-errors"

/**
 * Every runtime workflow action - submit, cancel, claim, release, approve,
 * reject. Any of them can change the queue, the badge count, an instance's
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
    submit: async (documentId: string): Promise<WorkflowInstanceDto | null> => {
      const instance = await run(() => submitDocumentToWorkflow(documentId), "Could not submit this document for approval.")
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
  }
}
