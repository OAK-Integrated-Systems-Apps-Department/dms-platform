"use client"

import { useState } from "react"
import { Loader2, ShieldCheck } from "lucide-react"

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
import { signatureErrorMessage } from "@/lib/signatures/signature-errors"

interface ConfirmPasswordDialogProps {
  title: string
  description: string
  /** Rejects with the raw error on failure - the dialog shows a friendly message and stays open. */
  onConfirm: (password: string) => Promise<void>
  onOpenChange: (open: boolean) => void
}

/**
 * Re-verifies the caller's password before a sensitive self-service change -
 * here, editing a saved visual signature from My Profile
 * (`VisualSignatureCard`). The backend re-checks this same password via
 * `StepUpAuthenticator`, the same step-up mechanism a real cryptographic
 * signature depends on - this dialog is purely the matching UI prompt, not
 * its own separate security control.
 */
export function ConfirmPasswordDialog({ title, description, onConfirm, onOpenChange }: ConfirmPasswordDialogProps) {
  const [password, setPassword] = useState("")
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    setError(null)
    setSubmitting(true)
    try {
      await onConfirm(password)
    } catch (err) {
      setPassword("")
      setError(signatureErrorMessage(err))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <AlertDialog open onOpenChange={onOpenChange}>
      <AlertDialogPortal>
        <AlertDialogPopup className="sm:max-w-sm">
          <form onSubmit={handleSubmit}>
            <span className="mx-auto mb-4 flex size-11 items-center justify-center rounded-btn bg-primary/10 text-primary">
              <ShieldCheck size={20} aria-hidden="true" />
            </span>
            <AlertDialogTitle>{title}</AlertDialogTitle>
            <AlertDialogDescription>{description}</AlertDialogDescription>

            {error && (
              <p className="mb-4 rounded-btn border border-destructive/30 bg-destructive/10 px-3 py-2 text-left text-[12.5px] text-destructive">
                {error}
              </p>
            )}

            <div className="mb-5 text-left">
              <Label htmlFor="confirm-password">Password</Label>
              <Input
                id="confirm-password"
                type="password"
                autoComplete="current-password"
                autoFocus
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
            </div>

            <div className="flex items-center justify-center gap-2.5">
              <AlertDialogClose
                type="button"
                disabled={submitting}
                className="border border-[var(--border-strong)] bg-card font-semibold text-foreground h-[38px] rounded-btn px-4 text-[13px] inline-flex items-center justify-center"
              >
                Cancel
              </AlertDialogClose>
              <button
                type="submit"
                disabled={submitting || password.length === 0}
                className="flex items-center gap-1.5 border border-primary bg-primary text-primary-foreground disabled:cursor-not-allowed disabled:opacity-60 h-[38px] rounded-btn px-4 text-[13px] font-semibold"
              >
                {submitting && <Loader2 size={14} className="animate-spin" />}
                Confirm
              </button>
            </div>
          </form>
        </AlertDialogPopup>
      </AlertDialogPortal>
    </AlertDialog>
  )
}
