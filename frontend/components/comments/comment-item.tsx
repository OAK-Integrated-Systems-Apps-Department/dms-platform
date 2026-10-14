"use client"

import { useState } from "react"
import { Lock, Pencil, Reply, Trash2 } from "lucide-react"

import { formatDateTime } from "@/lib/utils"

import type { CommentDto } from "@/lib/comments/api"

import { CommentComposer } from "./comment-composer"
import { fromWireBody } from "./mention-textarea"
import { MentionText } from "./mention-text"

interface CommentItemProps {
  comment: CommentDto
  currentUserId: string | undefined
  canWrite: boolean
  isAdmin: boolean
  busy: boolean
  onReply?: () => void
  onEdit: (body: string) => Promise<boolean>
  onDelete: () => void
}

const LINK = "flex items-center gap-1 text-[12px] font-semibold text-muted-foreground hover:text-foreground"

/** One comment: author, time, body with mention chips, and who was mentioned without document access. */
export function CommentItem({ comment, currentUserId, canWrite, isAdmin, busy, onReply, onEdit, onDelete }: CommentItemProps) {
  const [editing, setEditing] = useState(false)
  const mine = comment.author.userId === currentUserId
  const withoutAccess = comment.mentions.filter((m) => !m.hadDocumentAccess)

  return (
    <div className="flex gap-2.5">
      <span className="flex size-7 shrink-0 items-center justify-center rounded-full bg-muted text-[11.5px] font-bold text-muted-foreground">
        {(comment.author.displayName ?? "?").slice(0, 1).toUpperCase()}
      </span>
      <div className="min-w-0 flex-1">
        <p className="text-[12.5px]">
          <span className="font-semibold text-foreground">{comment.author.displayName ?? "Someone"}</span>
          <span className="ml-1.5 font-mono text-[11.5px] text-muted-foreground">{formatDateTime(comment.createdAt)}</span>
          {comment.editedAt && !comment.deleted && <span className="ml-1.5 text-[11.5px] text-muted-foreground">(edited)</span>}
        </p>
        {editing ? (
          <div className="mt-1.5">
            <CommentComposer
              id={`edit-${comment.commentId}`}
              initial={fromWireBody(comment.body)}
              submitLabel="Save"
              busy={busy}
              onCancel={() => setEditing(false)}
              onSubmit={async (body) => {
                const ok = await onEdit(body)
                if (ok) setEditing(false)
                return ok
              }}
            />
          </div>
        ) : (
          <p className={comment.deleted ? "mt-0.5 text-[13px] italic text-muted-foreground" : "mt-0.5 text-[13px] text-foreground"}>
            {comment.deleted ? "This comment was deleted." : <MentionText body={comment.body} />}
          </p>
        )}
        {withoutAccess.length > 0 && !comment.deleted && (
          <p className="mt-1 flex items-center gap-1 text-[11.5px] text-[var(--gold-foreground)]">
            <Lock size={11} /> {withoutAccess.map((m) => m.displayName ?? "Someone").join(", ")} can read this comment but
            can&apos;t open the document.
          </p>
        )}
        {!comment.deleted && !editing && (
          <div className="mt-1 flex gap-3">
            {onReply && canWrite && (
              <button type="button" className={LINK} onClick={onReply}>
                <Reply size={12} /> Reply
              </button>
            )}
            {mine && canWrite && (
              <button type="button" className={LINK} onClick={() => setEditing(true)}>
                <Pencil size={12} /> Edit
              </button>
            )}
            {(mine || isAdmin) && (
              <button type="button" className={`${LINK} hover:text-[var(--rust)]`} onClick={onDelete} disabled={busy}>
                <Trash2 size={12} /> Delete
              </button>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
