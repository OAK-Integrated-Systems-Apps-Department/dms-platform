"use client"

import { useState, type ReactNode } from "react"
import { AlertTriangle } from "lucide-react"
import { toast } from "sonner"

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
  /** Extra content under the description - e.g. a password field that must be filled in first. */
  children?: ReactNode
  /** Holds the confirm button back until what the children ask for is filled in. */
  confirmDisabled?: boolean
}

/**
 * Confirmation for the bulk import's Submit and Cancel actions. Modeled on
 * `FieldDeprecateDialog`: stays open and toasts the error on
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
  children,
  confirmDisabled = false,
}: ConfirmActionDialogProps) {
  const [submitting, setSubmitting] = useState(false)

  async function handleConfirm() {
    setSubmitting(true)
    const message = await onConfirm()
    setSubmitting(false)
    if (message) {
      // Shown as a toast like every other error in the app; the dialog stays open so the action can be retried.
      toast.error(message, { id: message })
      return
    }
    onOpenChange(false)
  }

  return (
    <AlertDialog
      open={open}
      onOpenChange={(next) => {
        if (submitting) return
        onOpenChange(next)
      }}
    >
      <AlertDialogPortal>
        <AlertDialogPopup className="max-w-[440px]">
          <span className="mx-auto mb-4 flex size-11 items-center justify-center rounded-btn bg-[var(--rust-tint)] text-[var(--rust)]">
            <AlertTriangle size={20} />
          </span>
          <AlertDialogTitle>{title}</AlertDialogTitle>
          <AlertDialogDescription>{description}</AlertDialogDescription>
          {children && <div className="mb-4 text-left">{children}</div>}

          <div className="flex items-center justify-center gap-2.5">
            <AlertDialogClose
              disabled={submitting}
              className="border border-[var(--border-strong)] bg-card font-semibold text-foreground disabled:opacity-60 h-[38px] rounded-btn px-4 text-[13px] inline-flex items-center justify-center"
            >
              {cancelLabel}
            </AlertDialogClose>
            <button
              type="button"
              onClick={handleConfirm}
              disabled={submitting || confirmDisabled}
              className="border border-[var(--rust)] bg-[var(--rust)] text-white disabled:opacity-60 h-[38px] rounded-btn px-4 text-[13px] font-semibold inline-flex items-center justify-center"
            >
              {submitting ? pendingLabel : confirmLabel}
            </button>
          </div>
        </AlertDialogPopup>
      </AlertDialogPortal>
    </AlertDialog>
  )
}
