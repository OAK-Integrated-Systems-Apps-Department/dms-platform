"use client"

import { useState, type ReactNode } from "react"
import { AlertTriangle } from "lucide-react"

import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogDescription,
  AlertDialogPopup,
  AlertDialogPortal,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"

interface ConfirmActionDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description: ReactNode
  confirmLabel: string
  pendingLabel: string
  cancelLabel?: string
  /** Performs the action and resolves with an error message, or null on success. */
  onConfirm: () => Promise<string | null>
}

/**
 * Confirmation for the bulk import's Submit and Cancel actions. Modeled on
 * `FieldDeprecateDialog`: stays open and shows the error inline on
 * failure, only closes once the action actually succeeds.
 */
export function ConfirmActionDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel,
  pendingLabel,
  cancelLabel = "Go back",
  onConfirm,
}: ConfirmActionDialogProps) {
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function handleConfirm() {
    setSubmitting(true)
    const message = await onConfirm()
    setSubmitting(false)
    if (message) {
      setError(message)
      return
    }
    onOpenChange(false)
  }

  return (
    <AlertDialog
      open={open}
      onOpenChange={(next) => {
        if (submitting) return
        setError(null)
        onOpenChange(next)
      }}
    >
      <AlertDialogPortal>
        <AlertDialogPopup className="max-w-[440px]">
          <span className="mx-auto mb-4 flex size-11 items-center justify-center rounded-md bg-[var(--rust-tint)] text-[var(--rust)]">
            <AlertTriangle size={20} />
          </span>
          <AlertDialogTitle>{title}</AlertDialogTitle>
          <AlertDialogDescription>{description}</AlertDialogDescription>

          {error && (
            <p
              role="alert"
              className="mb-4 rounded-btn border border-[var(--rust)] bg-[var(--rust-tint)] px-3.5 py-2.5 text-center text-[12.5px] text-[var(--rust)]"
            >
              {error}
            </p>
          )}

          <div className="flex items-center justify-center gap-2.5">
            <AlertDialogClose
              disabled={submitting}
              className="rounded-md border border-[var(--border-strong)] bg-card px-5 py-2.5 text-[13px] font-semibold text-foreground disabled:opacity-60"
            >
              {cancelLabel}
            </AlertDialogClose>
            <button
              type="button"
              onClick={handleConfirm}
              disabled={submitting}
              className="rounded-md border border-[var(--rust)] bg-[var(--rust)] px-5 py-2.5 text-[13px] font-bold tracking-[0.02em] text-white disabled:opacity-60"
            >
              {submitting ? pendingLabel : confirmLabel}
            </button>
          </div>
        </AlertDialogPopup>
      </AlertDialogPortal>
    </AlertDialog>
  )
}
