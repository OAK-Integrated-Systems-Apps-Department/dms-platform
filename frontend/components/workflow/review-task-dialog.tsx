"use client"

import { useEffect, useRef, useState } from "react"
import { useQueryClient } from "@tanstack/react-query"
import { Eye, EyeOff, Loader2, Undo2 } from "lucide-react"
import { toast } from "sonner"

import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogDescription,
  AlertDialogPopup,
  AlertDialogPortal,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import { useDocument } from "@/lib/documents/use-document"
import { formatDateTime } from "@/lib/utils"
import { closeReview, renewReviewLock, retractTask, reviewTask, type ReviewLockDto } from "@/lib/workflow/api"
import { workflowErrorMessage } from "@/lib/workflow/workflow-errors"

import { useDocumentPreview } from "./use-document-preview"

interface ReviewTaskDialogProps {
  taskId: string
  onOpenChange: (open: boolean) => void
}

/** How often the review lock is renewed while this dialog stays open - well inside the backend's own 2-minute TTL. */
const HEARTBEAT_INTERVAL_MS = 60_000

/**
 * V119: re-opening your own already-decided task. Step-up re-authentication gates even just VIEWING it
 * again (not only retracting) - see `WorkflowTaskServiceImpl#openForReview`'s own doc comment on the
 * backend for why. Once open, this also holds a soft lock on the stage (heartbeat-renewed every minute
 * while the dialog stays mounted) that blocks every other entry in the stage from acting - released on
 * close, successful retraction, or its own TTL if the tab is abandoned.
 * <p>
 * Closing without retracting is always a no-op: nothing changes, the lock is just released.
 */
export function ReviewTaskDialog({ taskId, onOpenChange }: ReviewTaskDialogProps) {
  const queryClient = useQueryClient()
  const preview = useDocumentPreview()
  const [password, setPassword] = useState("")
  const [showPassword, setShowPassword] = useState(false)
  const [opening, setOpening] = useState(false)
  const [openError, setOpenError] = useState<string | null>(null)
  const [lock, setLock] = useState<ReviewLockDto | null>(null)

  const [reason, setReason] = useState("")
  const [retractPassword, setRetractPassword] = useState("")
  const [showRetractPassword, setShowRetractPassword] = useState(false)
  const [retracting, setRetracting] = useState(false)
  const [retractError, setRetractError] = useState<string | null>(null)

  const { data: document } = useDocument(lock?.documentId ?? "")
  const retractedRef = useRef(false)

  // Heartbeat while the lock is held - lets the signer keep reading without the lock lapsing under them.
  useEffect(() => {
    if (!lock) return
    const interval = setInterval(() => {
      renewReviewLock(taskId)
        .then(setLock)
        .catch(() => {
          // Let it lapse quietly here - retract (or another renew) will surface the real
          // WORKFLOW_REVIEW_SESSION_ENDED error the moment it actually matters.
        })
    }, HEARTBEAT_INTERVAL_MS)
    return () => clearInterval(interval)
  }, [lock, taskId])

  // Release the lock on unmount unless a successful retract already did (closeReview afterward would
  // just be a harmless no-op, but skipping it avoids a pointless request).
  useEffect(() => {
    return () => {
      if (lock && !retractedRef.current) {
        closeReview(taskId).catch(() => {
          // Best-effort - the backend's own TTL cleans this up regardless.
        })
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function openForReview() {
    setOpening(true)
    setOpenError(null)
    try {
      const result = await reviewTask(taskId, password)
      setLock(result)
      setPassword("")
    } catch (error) {
      setOpenError(workflowErrorMessage(error, "Could not open this task for review."))
    } finally {
      setOpening(false)
    }
  }

  async function handleRetract() {
    setRetracting(true)
    setRetractError(null)
    try {
      await retractTask(taskId, reason, retractPassword)
      retractedRef.current = true
      toast.success("Decision retracted - you can decide again.")
      queryClient.invalidateQueries({ queryKey: ["workflow"] })
      onOpenChange(false)
    } catch (error) {
      setRetractPassword("")
      setRetractError(workflowErrorMessage(error, "Could not retract this decision."))
    } finally {
      setRetracting(false)
    }
  }

  return (
    <AlertDialog open onOpenChange={(open) => !open && onOpenChange(false)}>
      <AlertDialogPortal>
        <AlertDialogPopup className="sm:max-w-lg">
          <span className="mx-auto mb-4 flex size-11 items-center justify-center rounded-btn bg-primary/10 text-primary">
            <Undo2 size={20} aria-hidden="true" />
          </span>
          <AlertDialogTitle>Review your decision</AlertDialogTitle>
          <AlertDialogDescription>
            Re-enter your password to confirm this is really you, right now, before viewing it again - this
            locks the stage for other approvers while you do.
          </AlertDialogDescription>

          {!lock ? (
            <div className="mt-5 text-left">
              {openError && (
                <p className="mb-4 rounded-btn border border-destructive/30 bg-destructive/10 px-3 py-2 text-[12.5px] text-destructive">
                  {openError}
                </p>
              )}
              <div className="mb-5">
                <Label htmlFor="review-password">Password</Label>
                <div className="relative">
                  <Input
                    id="review-password"
                    type={showPassword ? "text" : "password"}
                    autoComplete="current-password"
                    className="pr-10"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                  />
                  <button
                    type="button"
                    onClick={() => setShowPassword((prev) => !prev)}
                    tabIndex={-1}
                    aria-label={showPassword ? "Hide password" : "Show password"}
                    className="absolute top-1/2 right-3 -translate-y-1/2 text-muted-foreground transition-colors hover:text-foreground"
                  >
                    {showPassword ? <EyeOff size={16} /> : <Eye size={16} />}
                  </button>
                </div>
              </div>
              <div className="flex items-center justify-center gap-2.5">
                <AlertDialogClose
                  type="button"
                  className="border border-[var(--border-strong)] bg-card font-semibold text-foreground h-[38px] rounded-btn px-4 text-[13px] inline-flex items-center justify-center"
                >
                  Cancel
                </AlertDialogClose>
                <button
                  type="button"
                  onClick={openForReview}
                  disabled={opening || !password}
                  className="flex items-center gap-1.5 border border-primary bg-primary text-primary-foreground disabled:cursor-not-allowed disabled:opacity-60 h-[38px] rounded-btn px-4 text-[13px] font-semibold"
                >
                  {opening && <Loader2 size={14} className="animate-spin" />}
                  Review
                </button>
              </div>
            </div>
          ) : (
            <div className="mt-5 text-left">
              <div className="mb-4 rounded-btn border border-[var(--border-strong)] bg-muted px-3 py-2.5 text-[12.5px]">
                <p>
                  <strong className="text-foreground">Your decision:</strong>{" "}
                  {lock.decision === "approve" ? "Approved" : "Rejected"}
                  {lock.actedAt && <> · {formatDateTime(lock.actedAt)}</>}
                </p>
                {lock.decisionReason && <p className="mt-1 text-foreground">{lock.decisionReason}</p>}
              </div>

              {document && (
                <button
                  type="button"
                  onClick={() => preview.open(document)}
                  className="mb-4 flex items-center gap-1.5 border border-[var(--border-strong)] text-foreground hover:bg-muted h-[38px] rounded-btn px-4 text-[13px] font-semibold justify-center"
                >
                  <Eye size={13} />
                  Read document
                </button>
              )}

              <div className="mb-4">
                <Label htmlFor="review-reason">Reason to retract (required)</Label>
                <Textarea
                  id="review-reason"
                  rows={3}
                  maxLength={2000}
                  placeholder="Why you're undoing this decision…"
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                />
              </div>

              <div className="mb-4">
                <Label htmlFor="review-retract-password">Password</Label>
                <div className="relative">
                  <Input
                    id="review-retract-password"
                    type={showRetractPassword ? "text" : "password"}
                    autoComplete="current-password"
                    className="pr-10"
                    value={retractPassword}
                    onChange={(e) => setRetractPassword(e.target.value)}
                  />
                  <button
                    type="button"
                    onClick={() => setShowRetractPassword((prev) => !prev)}
                    tabIndex={-1}
                    aria-label={showRetractPassword ? "Hide password" : "Show password"}
                    className="absolute top-1/2 right-3 -translate-y-1/2 text-muted-foreground transition-colors hover:text-foreground"
                  >
                    {showRetractPassword ? <EyeOff size={16} /> : <Eye size={16} />}
                  </button>
                </div>
              </div>

              {retractError && (
                <p className="mb-4 rounded-btn border border-destructive/30 bg-destructive/10 px-3 py-2 text-[12.5px] text-destructive">
                  {retractError}
                </p>
              )}

              <div className="flex items-center justify-center gap-2.5">
                <button
                  type="button"
                  onClick={() => onOpenChange(false)}
                  disabled={retracting}
                  className="border border-[var(--border-strong)] bg-card font-semibold text-foreground disabled:cursor-not-allowed disabled:opacity-60 h-[38px] rounded-btn px-4 text-[13px] inline-flex items-center justify-center"
                >
                  Close (no change)
                </button>
                <button
                  type="button"
                  onClick={handleRetract}
                  disabled={retracting || !reason.trim() || !retractPassword}
                  className="flex items-center gap-1.5 border border-[var(--rust)] bg-[var(--rust)] text-white disabled:cursor-not-allowed disabled:opacity-60 h-[38px] rounded-btn px-4 text-[13px] font-semibold"
                >
                  {retracting && <Loader2 size={14} className="animate-spin" />}
                  Retract and re-decide
                </button>
              </div>
            </div>
          )}
        </AlertDialogPopup>
      </AlertDialogPortal>
      {preview.element}
    </AlertDialog>
  )
}
