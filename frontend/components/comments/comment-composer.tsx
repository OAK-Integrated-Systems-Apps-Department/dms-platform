"use client"

import { useState } from "react"
import { Loader2, Send } from "lucide-react"

import { MentionTextarea, toWireBody, type MentionDraft } from "./mention-textarea"

interface CommentComposerProps {
  id: string
  initial?: MentionDraft
  submitLabel: string
  placeholder?: string
  busy: boolean
  /** Resolves true when saved - the box then clears. */
  onSubmit: (body: string) => Promise<boolean>
  onCancel?: () => void
}

const MAX_BODY = 4000

/** A comment, reply or edit box. Mentions are typed as "@Name" and sent as tokens. */
export function CommentComposer({ id, initial, submitLabel, placeholder, busy, onSubmit, onCancel }: CommentComposerProps) {
  const [draft, setDraft] = useState<MentionDraft>(initial ?? { text: "", mentions: [] })
  const body = toWireBody({ text: draft.text.trim(), mentions: draft.mentions })
  const tooLong = body.length > MAX_BODY
  const canSubmit = draft.text.trim().length > 0 && !tooLong && !busy

  async function submit() {
    if (!canSubmit) return
    if (await onSubmit(body)) setDraft({ text: "", mentions: [] })
  }

  return (
    <div className="flex flex-col gap-2">
      <MentionTextarea
        id={id}
        value={draft}
        onChange={setDraft}
        placeholder={placeholder ?? "Write a comment - type @ to mention someone"}
        disabled={busy}
        onSubmitShortcut={() => void submit()}
      />
      <div className="flex items-center justify-between gap-2">
        <span className={tooLong ? "text-[12px] text-destructive" : "text-[11.5px] text-muted-foreground"}>
          {tooLong ? "This comment is too long." : "Ctrl+Enter to send"}
        </span>
        <div className="flex gap-2">
          {onCancel && (
            <button
              type="button"
              onClick={onCancel}
              disabled={busy}
              className="rounded-btn border border-[var(--border-strong)] bg-card font-semibold text-foreground h-[38px] px-4 text-[13px] inline-flex items-center justify-center"
            >
              Cancel
            </button>
          )}
          <button
            type="button"
            onClick={() => void submit()}
            disabled={!canSubmit}
            className="flex items-center gap-1.5 border border-primary bg-primary text-primary-foreground disabled:cursor-not-allowed disabled:opacity-50 h-[38px] rounded-btn px-4 text-[13px] font-semibold justify-center"
          >
            {busy ? <Loader2 size={13} className="animate-spin" /> : <Send size={13} />}
            {submitLabel}
          </button>
        </div>
      </div>
    </div>
  )
}
