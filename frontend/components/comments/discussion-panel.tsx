"use client"

import { LoadFailed } from "@/components/ui/load-failed"
import { useMemo, useState } from "react"
import { History, MessagesSquare } from "lucide-react"

import { Panel } from "@/components/ui/panel"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { useCurrentUser } from "@/lib/auth/use-current-user"
import { useIsTenantAdmin } from "@/lib/auth/use-is-tenant-admin"
import { usePermissions } from "@/lib/auth/use-permissions"
import { useCommentActions, useComments } from "@/lib/comments/use-comments"
import { useWorkflowTimeline } from "@/lib/workflow/use-workflow-instance"
import { ACTION_TYPE_LABELS } from "@/lib/workflow/workflow-badges"
import { cn, formatDateTime } from "@/lib/utils"

import type { CommentThreadDto } from "@/lib/comments/api"

import { CommentComposer } from "./comment-composer"
import { CommentItem } from "./comment-item"

interface DiscussionPanelProps {
  documentId: string
  /** On a workflow run's page: comments about that run, and its decisions in the full history. */
  workflowInstanceId?: string | null
  title?: string
}

/** Decisions and request events worth showing between comments in "Full history". */
const DECISION_TYPES = new Set([
  "submitted",
  "approved",
  "rejected",
  "delegated",
  "reassigned",
  "escalated",
  "info_requested",
  "info_provided",
  "info_declined",
  "workflow_approved",
  "workflow_rejected",
  "cancelled",
])

/**
 * Threaded discussion of a document (decision 9), optionally scoped to one
 * workflow run. Reading needs docm.documents.view; posting also needs
 * docm.document-comments.create. Refreshes every 15 seconds. "Full history"
 * interleaves the run's decisions with the comments, by time.
 */
export function DiscussionPanel({ documentId, workflowInstanceId = null, title = "Discussion" }: DiscussionPanelProps) {
  const { data: me } = useCurrentUser()
  const { isTenantAdmin } = useIsTenantAdmin()
  const { hasPermission } = usePermissions()
  const canRead = hasPermission(PermissionCodes.DOCM_DOCUMENTS_VIEW)
  const canWrite = canRead && hasPermission(PermissionCodes.DOCM_COMMENTS_CREATE)
  const comments = useComments(documentId, workflowInstanceId, canRead)
  const timeline = useWorkflowTimeline(workflowInstanceId ?? undefined)
  const actions = useCommentActions(documentId)
  const [replyingTo, setReplyingTo] = useState<string | null>(null)
  const [fullHistory, setFullHistory] = useState(false)

  const threads = useMemo(() => comments.data?.content ?? [], [comments.data])
  const entries = useMemo(() => {
    const items: { at: string; thread?: CommentThreadDto; event?: { label: string; reason: string | null } }[] =
      threads.map((t) => ({ at: t.root.createdAt, thread: t }))
    if (fullHistory && workflowInstanceId) {
      for (const a of timeline.data?.content ?? []) {
        if (!DECISION_TYPES.has(a.actionType)) continue
        const label = ACTION_TYPE_LABELS[a.actionType] ?? a.actionType
        items.push({ at: a.occurredAt, event: { label: a.actor ? `${a.actor.displayName ?? "Someone"} ${label}` : label, reason: a.reason } })
      }
    }
    return items.sort((x, y) => x.at.localeCompare(y.at))
  }, [threads, fullHistory, workflowInstanceId, timeline.data])

  if (!canRead) return null

  return (
    <Panel title={title}>
      <div className="flex flex-col gap-4 pt-3">
        {workflowInstanceId && (
          <button
            type="button"
            onClick={() => setFullHistory((v) => !v)}
            className={cn(
              "flex items-center gap-1.5 self-start border h-[38px] rounded-btn px-4 text-[13px] font-semibold justify-center",
              fullHistory ? "border-primary bg-primary/10 text-primary" : "border-[var(--border-strong)] text-muted-foreground"
            )}
          >
            <History size={12} /> {fullHistory ? "Showing decisions too" : "Full history"}
          </button>
        )}

        {comments.isPending ? (
          <p className="text-[13px] text-muted-foreground">Loading the discussion…</p>
        ) : comments.isError ? (
          <LoadFailed error={comments.error} fallback="Could not load the discussion." />
        ) : entries.length === 0 ? (
          <p className="flex items-center gap-1.5 text-[13px] text-muted-foreground">
            <MessagesSquare size={14} /> No comments yet.
          </p>
        ) : (
          <ul className="flex flex-col gap-4">
            {entries.map((entry, i) =>
              entry.thread ? (
                <li key={entry.thread.root.commentId} className="flex flex-col gap-3">
                  <CommentItem
                    comment={entry.thread.root}
                    currentUserId={me?.userId}
                    canWrite={canWrite}
                    isAdmin={isTenantAdmin}
                    busy={actions.isSaving}
                    onReply={() => setReplyingTo(entry.thread!.root.commentId)}
                    onEdit={async (body) => (await actions.edit(entry.thread!.root.commentId, body)) !== null}
                    onDelete={() => void actions.remove(entry.thread!.root.commentId)}
                  />
                  {(entry.thread.replies.length > 0 || replyingTo === entry.thread.root.commentId) && (
                    <div className="ml-9 flex flex-col gap-3 border-l-2 border-border pl-3">
                      {entry.thread.replies.map((reply) => (
                        <CommentItem
                          key={reply.commentId}
                          comment={reply}
                          currentUserId={me?.userId}
                          canWrite={canWrite}
                          isAdmin={isTenantAdmin}
                          busy={actions.isSaving}
                          onReply={() => setReplyingTo(entry.thread!.root.commentId)}
                          onEdit={async (body) => (await actions.edit(reply.commentId, body)) !== null}
                          onDelete={() => void actions.remove(reply.commentId)}
                        />
                      ))}
                      {replyingTo === entry.thread.root.commentId && (
                        <CommentComposer
                          id={`reply-${entry.thread.root.commentId}`}
                          submitLabel="Reply"
                          placeholder="Write a reply…"
                          busy={actions.isSaving}
                          onCancel={() => setReplyingTo(null)}
                          onSubmit={async (body) => {
                            const ok = (await actions.post(body, entry.thread!.root.commentId, workflowInstanceId)) !== null
                            if (ok) setReplyingTo(null)
                            return ok
                          }}
                        />
                      )}
                    </div>
                  )}
                </li>
              ) : (
                <li key={`event-${i}`} className="flex items-start gap-2 rounded-btn bg-muted px-3 py-2 text-[12.5px] text-muted-foreground">
                  <History size={13} className="mt-0.5 shrink-0" />
                  <span>
                    <span className="text-foreground">{entry.event!.label}</span>
                    <span className="ml-1.5 font-mono text-[11.5px]">{formatDateTime(entry.at)}</span>
                    {entry.event!.reason && <span className="block whitespace-pre-wrap text-foreground">{entry.event!.reason}</span>}
                  </span>
                </li>
              )
            )}
          </ul>
        )}

        {canWrite ? (
          <CommentComposer
            id={`comment-${documentId}`}
            submitLabel="Comment"
            busy={actions.isSaving}
            onSubmit={async (body) => (await actions.post(body, null, workflowInstanceId)) !== null}
          />
        ) : (
          <p className="text-[12px] text-muted-foreground">You can read this discussion but not post to it.</p>
        )}
      </div>
    </Panel>
  )
}
