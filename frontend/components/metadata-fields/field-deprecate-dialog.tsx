"use client"

import { useState } from "react"
import { ArchiveX } from "lucide-react"

import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogDescription,
  AlertDialogPopup,
  AlertDialogPortal,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"

import type { MetadataFieldResponseDto } from "@/lib/metadata-fields/api"

interface FieldDeprecateDialogProps {
  field: MetadataFieldResponseDto
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Performs the deprecate and resolves with an error message, or null on success. */
  onConfirm: () => Promise<string | null>
}

/**
 * Deprecate confirmation — the alternative offered when deleting a field is
 * blocked because it's still referenced by a document type (MDTM alternate
 * flow 2). Modeled on `RoleDeleteDialog`: stays open and shows the error
 * inline on failure, only closes once the deprecate actually succeeds.
 */
export function FieldDeprecateDialog({ field, open, onOpenChange, onConfirm }: FieldDeprecateDialogProps) {
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
        <AlertDialogPopup className="max-w-[460px]">
          <span className="mx-auto mb-4 flex size-11 items-center justify-center rounded-btn bg-[var(--gold-tint)] text-[var(--gold-foreground)]">
            <ArchiveX size={20} />
          </span>
          <AlertDialogTitle>Deprecate this field?</AlertDialogTitle>
          <AlertDialogDescription>
            <strong className="font-semibold text-foreground">{field.label}</strong> will stay usable on documents
            that already reference it, but will be excluded from new document-type field pickers. This can be
            reversed by reactivating the field.
          </AlertDialogDescription>

          {error && (
            <p className="mb-4 rounded-btn border border-[var(--rust)] bg-[var(--rust-tint)] px-3.5 py-2.5 text-center text-[12.5px] text-[var(--rust)]">
              {error}
            </p>
          )}

          <div className="flex items-center justify-center gap-2.5">
            <AlertDialogClose
              disabled={submitting}
              className="border border-[var(--border-strong)] bg-card font-semibold text-foreground disabled:opacity-60 h-[38px] rounded-btn px-4 text-[13px] inline-flex items-center justify-center"
            >
              Cancel
            </AlertDialogClose>
            <button
              type="button"
              onClick={handleConfirm}
              disabled={submitting}
              className="border border-[var(--gold)] bg-[var(--gold)] text-[var(--gold-foreground)] disabled:opacity-60 h-[38px] rounded-btn px-4 text-[13px] font-semibold inline-flex items-center justify-center"
            >
              {submitting ? "Deprecating…" : "Deprecate Field"}
            </button>
          </div>
        </AlertDialogPopup>
      </AlertDialogPortal>
    </AlertDialog>
  )
}
