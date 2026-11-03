"use client"

import { useState } from "react"
import { Loader2 } from "lucide-react"

import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogDescription,
  AlertDialogPopup,
  AlertDialogPortal,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"

/** Force-release and legal hold both need a written reason (HIGH audit) - this asks for it, then calls `onConfirm`. */
export function ReasonDialog({
  title,
  description,
  confirmLabel,
  onConfirm,
  onOpenChange,
}: {
  title: string
  description: string
  confirmLabel: string
  onConfirm: (reason: string) => Promise<unknown>
  onOpenChange: (open: boolean) => void
}) {
  const [reason, setReason] = useState("")
  const [pending, setPending] = useState(false)
  const trimmed = reason.trim()

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (!trimmed) return
    setPending(true)
    try {
      await onConfirm(trimmed)
      onOpenChange(false)
    } catch {
      // The mutation already toasted the server's message; keep the dialog open so the reason isn't lost.
    } finally {
      setPending(false)
    }
  }

  return (
    <AlertDialog open onOpenChange={onOpenChange}>
      <AlertDialogPortal>
        <AlertDialogPopup className="sm:max-w-lg">
          <form onSubmit={submit}>
            <AlertDialogTitle>{title}</AlertDialogTitle>
            <AlertDialogDescription>{description}</AlertDialogDescription>
            <div className="mb-5 text-left">
              <Label htmlFor="checkout-reason">Reason (required)</Label>
              <Textarea
                id="checkout-reason"
                rows={3}
                maxLength={2000}
                value={reason}
                onChange={(event) => setReason(event.target.value)}
              />
            </div>
            <div className="flex items-center justify-center gap-2.5">
              <AlertDialogClose
                type="button"
                disabled={pending}
                className="rounded-md border border-[var(--border-strong)] bg-card px-5 py-2.5 text-[13px] font-semibold text-foreground"
              >
                Cancel
              </AlertDialogClose>
              <button
                type="submit"
                disabled={pending || !trimmed}
                className="flex items-center gap-1.5 rounded-md border border-primary bg-primary px-5 py-2.5 text-[13px] font-bold text-primary-foreground disabled:cursor-not-allowed disabled:opacity-60"
              >
                {pending && <Loader2 size={14} className="animate-spin" />}
                {confirmLabel}
              </button>
            </div>
          </form>
        </AlertDialogPopup>
      </AlertDialogPortal>
    </AlertDialog>
  )
}
