import { API_BASE_URL } from "@/lib/api-config"
import { apiFetch, apiFetchJson } from "@/lib/api-fetch"
import type { PageDto, WorkflowUserRefDto } from "@/lib/workflow/api"

const BASE = `${API_BASE_URL}/api/v1`

/** Mirrors backend/dms-core's CommentMentionResponse. hadDocumentAccess: could they open the document when mentioned. */
export interface CommentMentionDto {
  userId: string
  displayName: string | null
  hadDocumentAccess: boolean
}

/**
 * Mirrors backend/dms-core's CommentResponse. The body holds mentions as
 * `@[Name](user:<id>)` tokens; a deleted comment reads "[deleted]".
 */
export interface CommentDto {
  commentId: string
  documentId: string
  workflowInstanceId: string | null
  parentCommentId: string | null
  body: string
  author: WorkflowUserRefDto
  createdAt: string
  editedAt: string | null
  deleted: boolean
  mentions: CommentMentionDto[]
}

/** A root comment and its replies (threads are two levels deep). */
export interface CommentThreadDto {
  root: CommentDto
  replies: CommentDto[]
}

/** Mirrors backend/dms-core's MentionResponse. canOpenDocument: the caller holds docm.documents.view. */
export interface MentionDto {
  mentionId: string
  comment: CommentDto
  documentId: string
  documentTitle: string | null
  canOpenDocument: boolean
  createdAt: string
}

/** Threads of a document, oldest first; workflowInstanceId narrows to one workflow run. `page` is 0-indexed. */
export async function fetchComments(
  documentId: string,
  workflowInstanceId: string | null,
  page: number,
  size: number
): Promise<PageDto<CommentThreadDto>> {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  if (workflowInstanceId) params.set("workflowInstanceId", workflowInstanceId)
  return apiFetchJson(`${BASE}/documents/${documentId}/comments?${params.toString()}`, undefined, "Could not load the discussion.")
}

export async function createComment(
  documentId: string,
  body: string,
  parentCommentId: string | null,
  workflowInstanceId: string | null
): Promise<CommentDto> {
  return apiFetchJson(
    `${BASE}/documents/${documentId}/comments`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ body, parentCommentId, workflowInstanceId }),
    },
    "Could not post your comment."
  )
}

export async function updateComment(commentId: string, body: string): Promise<CommentDto> {
  return apiFetchJson(
    `${BASE}/comments/${commentId}`,
    { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ body }) },
    "Could not save your comment."
  )
}

export async function deleteComment(commentId: string): Promise<void> {
  await apiFetch(`${BASE}/comments/${commentId}`, { method: "DELETE" }, "Could not delete the comment.")
}

/** Comments I was mentioned in, newest first. `page` is 0-indexed. */
export async function fetchMyMentions(page: number, size: number): Promise<PageDto<MentionDto>> {
  return apiFetchJson(`${BASE}/mentions/my?page=${page}&size=${size}`, undefined, "Could not load your mentions.")
}
