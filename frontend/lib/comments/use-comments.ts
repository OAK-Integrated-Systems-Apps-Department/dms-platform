"use client"

import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import { friendlyMessage } from "@/lib/api-error"

import { createComment, deleteComment, fetchComments, fetchMyMentions, updateComment } from "./api"

/** WA-NFR-001's near-real-time, as for My Tasks: poll, and refetch on focus. */
const POLL_MS = 15_000
const PAGE_SIZE = 100

export function useComments(documentId: string | undefined, workflowInstanceId: string | null, enabled = true) {
  return useQuery({
    queryKey: ["comments", documentId, workflowInstanceId],
    queryFn: () => fetchComments(documentId!, workflowInstanceId, 0, PAGE_SIZE),
    enabled: Boolean(documentId) && enabled,
    refetchInterval: POLL_MS,
    refetchOnWindowFocus: true,
    retry: false,
  })
}

export function useMyMentions(page: number, size: number) {
  return useQuery({
    queryKey: ["mentions", page, size],
    queryFn: () => fetchMyMentions(page - 1, size),
    refetchInterval: POLL_MS,
    refetchOnWindowFocus: true,
    placeholderData: keepPreviousData,
  })
}

/** Post, edit and delete; each success refreshes every discussion and the workflow timeline ("commented"). */
export function useCommentActions(documentId: string) {
  const queryClient = useQueryClient()
  const mutation = useMutation({
    mutationFn: async ({ run }: { run: () => Promise<unknown>; failure: string }) => run(),
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ["comments", documentId] })
      queryClient.invalidateQueries({ queryKey: ["workflow"] })
    },
    onError: (error, { failure }) => toast.error(friendlyMessage(error, failure)),
  })

  async function run<T>(fn: () => Promise<T>, failure: string): Promise<T | null> {
    try {
      return (await mutation.mutateAsync({ run: fn, failure })) as T
    } catch {
      return null
    }
  }

  return {
    isSaving: mutation.isPending,
    post: (body: string, parentCommentId: string | null, workflowInstanceId: string | null) =>
      run(() => createComment(documentId, body, parentCommentId, workflowInstanceId), "Could not post your comment."),
    edit: (commentId: string, body: string) => run(() => updateComment(commentId, body), "Could not save your comment."),
    remove: (commentId: string) =>
      run(async () => {
        await deleteComment(commentId)
        return true
      }, "Could not delete the comment."),
  }
}
