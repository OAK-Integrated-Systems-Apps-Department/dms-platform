"use client"

import { zodResolver } from "@hookform/resolvers/zod"
import { useForm } from "react-hook-form"
import { Loader2, Unlock } from "lucide-react"
import { z } from "zod"

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
import { useSignatureActions } from "@/lib/signatures/use-signatures"

const unlockSchema = z.object({
  reason: z.string().trim().min(1, "A reason is required to unlock").max(2000, "Keep it under 2000 characters"),
})
type UnlockFormValues = z.infer<typeof unlockSchema>

/**
 * Unlocking needs DSI_UNLOCK_SIGNED_DOCUMENTS (checked by the caller before
 * rendering this) and a mandatory reason (HIGH audit). Does NOT invalidate
 * the signature(s) that locked the document - they stay valid for the
 * version they signed.
 */
export function UnlockDocumentDialog({
  documentId,
  onOpenChange,
}: {
  documentId: string
  onOpenChange: (open: boolean) => void
}) {
  const { unlock } = useSignatureActions()
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<UnlockFormValues>({ resolver: zodResolver(unlockSchema), defaultValues: { reason: "" } })

  async function submit(values: UnlockFormValues) {
    const ok = await unlock(documentId, values.reason.trim())
    if (ok) onOpenChange(false)
  }

  return (
    <AlertDialog open onOpenChange={onOpenChange}>
      <AlertDialogPortal>
        <AlertDialogPopup className="sm:max-w-lg">
          <form onSubmit={handleSubmit(submit)}>
            <span className="mx-auto mb-4 flex size-11 items-center justify-center rounded-md bg-[var(--gold-tint)] text-[var(--gold-foreground)]">
              <Unlock size={20} aria-hidden="true" />
            </span>
            <AlertDialogTitle>Unlock this document?</AlertDialogTitle>
            <AlertDialogDescription>
              New versions, restore, metadata edits, move and delete will be allowed again. The signature that locked
              it stays valid for the version it signed.
            </AlertDialogDescription>

            <div className="mb-5 text-left">
              <Label htmlFor="unlock-reason">Reason (required)</Label>
              <Textarea
                id="unlock-reason"
                rows={3}
                maxLength={2000}
                placeholder="Why this document needs to be unlocked…"
                aria-invalid={Boolean(errors.reason)}
                {...register("reason")}
              />
              {errors.reason && <p className="mt-1.5 text-[12px] text-destructive">{errors.reason.message}</p>}
            </div>

            <div className="flex items-center justify-center gap-2.5">
              <AlertDialogClose
                type="button"
                disabled={isSubmitting}
                className="rounded-md border border-[var(--border-strong)] bg-card px-5 py-2.5 text-[13px] font-semibold text-foreground"
              >
                Cancel
              </AlertDialogClose>
              <button
                type="submit"
                disabled={isSubmitting}
                className="flex items-center gap-1.5 rounded-md border border-primary bg-primary px-5 py-2.5 text-[13px] font-bold tracking-[0.02em] text-primary-foreground disabled:cursor-not-allowed disabled:opacity-60"
              >
                {isSubmitting && <Loader2 size={14} className="animate-spin" />}
                Unlock
              </button>
            </div>
          </form>
        </AlertDialogPopup>
      </AlertDialogPortal>
    </AlertDialog>
  )
}
