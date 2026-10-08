"use client"

import { useState, type ReactNode } from "react"

import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogDescription,
  AlertDialogPopup,
  AlertDialogPortal,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { Input } from "@/components/ui/input"

interface ActionDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description?: ReactNode
  confirmLabel: string
  pendingLabel: string
  /** Resolves to an error message to show, or null when done (the dialog then closes). */
  onConfirm: () => Promise<string | null>
  disabled?: boolean
  children?: ReactNode
}

/** A small dialog with its own content (an input, a folder picker) and one action. */
export function ActionDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel,
  pendingLabel,
  onConfirm,
  disabled,
  children,
}: ActionDialogProps) {
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function handleConfirm() {
    setSubmitting(true)
    setError(null)
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
        <AlertDialogPopup className="max-w-[460px] text-left">
          <AlertDialogTitle>{title}</AlertDialogTitle>
          {description && (
            <AlertDialogDescription>{description}</AlertDialogDescription>
          )}
          <form
            onSubmit={(e) => {
              e.preventDefault()
              if (!disabled && !submitting) void handleConfirm()
            }}
          >
            <div className="mb-4">{children}</div>
            {error && (
              <p
                role="alert"
                className="mb-4 rounded-btn border border-[var(--rust)] bg-[var(--rust-tint)] px-3.5 py-2.5 text-[12.5px] text-[var(--rust)]"
              >
                {error}
              </p>
            )}
            <div className="flex items-center justify-end gap-2.5">
              <AlertDialogClose
                disabled={submitting}
                className="rounded-md border border-[var(--border-strong)] bg-card px-5 py-2.5 text-[13px] font-semibold text-foreground disabled:opacity-60"
              >
                Cancel
              </AlertDialogClose>
              <button
                type="submit"
                disabled={submitting || disabled}
                className="rounded-md border border-primary bg-primary px-5 py-2.5 text-[13px] font-bold text-primary-foreground disabled:opacity-60"
              >
                {submitting ? pendingLabel : confirmLabel}
              </button>
            </div>
          </form>
        </AlertDialogPopup>
      </AlertDialogPortal>
    </AlertDialog>
  )
}

/** Name a new folder or rename one. Remount (new `key`) per opening. */
export function FolderNameDialog({
  open,
  onOpenChange,
  title,
  description,
  initialName = "",
  confirmLabel,
  onSubmit,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description?: ReactNode
  initialName?: string
  confirmLabel: string
  onSubmit: (name: string) => Promise<string | null>
}) {
  const [name, setName] = useState(initialName)
  return (
    <ActionDialog
      open={open}
      onOpenChange={onOpenChange}
      title={title}
      description={description}
      confirmLabel={confirmLabel}
      pendingLabel="Saving…"
      disabled={!name.trim()}
      onConfirm={() => onSubmit(name.trim())}
    >
      <Input
        autoFocus
        aria-label="Folder name"
        placeholder="Folder name"
        maxLength={255}
        value={name}
        onChange={(e) => setName(e.target.value)}
      />
    </ActionDialog>
  )
}
