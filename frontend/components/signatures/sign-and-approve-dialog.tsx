"use client"

import { useState } from "react"
import { zodResolver } from "@hookform/resolvers/zod"
import { useForm } from "react-hook-form"
import { Loader2, PenLine } from "lucide-react"
import { toast } from "sonner"

import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogDescription,
  AlertDialogPopup,
  AlertDialogPortal,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { Checkbox } from "@/components/ui/checkbox"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import { OtpInput } from "@/components/auth/otp-input"
import { useSignatureActions } from "@/lib/signatures/use-signatures"
import { signatureErrorMessage } from "@/lib/signatures/signature-errors"
import { INSTANCE_STATUS_LABELS } from "@/lib/workflow/workflow-badges"

import { signAndApproveSchema, type SignAndApproveFormValues } from "./schema"

interface SignAndApproveDialogProps {
  taskId: string
  documentTitle: string
  /** e.g. "Stage 2 of 3: Legal review". */
  stageLabel: string
  versionChanged?: boolean
  onOpenChange: (open: boolean) => void
}

/**
 * 1.4 Digital Signature Integration: approving a stage that
 * `requires_signature` opens this instead of `TaskActionDialog` - comment,
 * password, authenticator code (if the signer has MFA), and an explicit
 * consent checkbox, all re-checked server-side the moment this submits
 * (step-up re-authentication, decision 2).
 * <p>
 * On failure the dialog stays open and the comment is kept exactly as
 * typed - only `password`/`totp` are cleared, never resubmitted or shown
 * again, so a wrong attempt never lingers in the form past its own
 * rejection. Neither field is ever logged (see `SignAndApproveRequestDto`'s
 * own `toString`-equivalent note on the backend).
 */
export function SignAndApproveDialog({
  taskId,
  documentTitle,
  stageLabel,
  versionChanged,
  onOpenChange,
}: SignAndApproveDialogProps) {
  const { signAndApprove } = useSignatureActions()
  const [formError, setFormError] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    watch,
    setValue,
    formState: { errors, isSubmitting },
  } = useForm<SignAndApproveFormValues>({
    resolver: zodResolver(signAndApproveSchema),
    defaultValues: { comment: "", password: "", totp: "", consent: false },
  })
  const totp = watch("totp")
  const consent = watch("consent")

  async function submit(values: SignAndApproveFormValues) {
    setFormError(null)
    try {
      const result = await signAndApprove.mutateAsync({
        taskId,
        request: {
          comment: values.comment.trim() || null,
          meaning: "approved",
          consent: values.consent,
          password: values.password,
          totp: values.totp.trim() || null,
        },
      })
      if (result.instanceStatus !== "in_progress") {
        toast.success(
          `Signed and approved. The workflow is now ${INSTANCE_STATUS_LABELS[result.instanceStatus].toLowerCase()}.`
        )
      } else {
        toast.success("Signed and approved.")
      }
      onOpenChange(false)
    } catch (error) {
      // Never resubmit or redisplay a rejected password/OTP - clear both and
      // let the signer retype them; the comment they wrote is kept as-is.
      setValue("password", "")
      setValue("totp", "")
      setFormError(signatureErrorMessage(error))
    }
  }

  return (
    <AlertDialog open onOpenChange={onOpenChange}>
      <AlertDialogPortal>
        <AlertDialogPopup className="sm:max-w-xl">
          <form onSubmit={handleSubmit(submit)}>
            <span className="mx-auto mb-4 flex size-11 items-center justify-center rounded-md bg-primary/10 text-primary">
              <PenLine size={20} aria-hidden="true" />
            </span>
            <AlertDialogTitle>Sign and approve this document?</AlertDialogTitle>
            <AlertDialogDescription>
              <strong className="font-semibold text-foreground">{documentTitle}</strong>
              <br />
              <span className="text-[13.5px]">{stageLabel}</span>
            </AlertDialogDescription>

            {versionChanged && (
              <p className="mb-4 rounded-btn border border-[var(--gold)] bg-[var(--gold-tint)] px-3 py-2 text-left text-[12.5px] text-[var(--gold-foreground)]">
                A newer version of this document was uploaded after it was submitted. You are signing the submitted
                version.
              </p>
            )}

            {formError && (
              <p className="mb-4 rounded-btn border border-destructive/30 bg-destructive/10 px-3 py-2 text-left text-[12.5px] text-destructive">
                {formError}
              </p>
            )}

            <div className="mb-4 text-left">
              <Label htmlFor="sign-approve-comment">Comment (optional)</Label>
              <Textarea
                id="sign-approve-comment"
                rows={3}
                maxLength={2000}
                placeholder="Anything the submitter or the next approver should know…"
                aria-invalid={Boolean(errors.comment)}
                {...register("comment")}
              />
              {errors.comment && <p className="mt-1.5 text-[12px] text-destructive">{errors.comment.message}</p>}
            </div>

            <div className="mb-4 text-left">
              <Label htmlFor="sign-approve-password">Password</Label>
              <Input
                id="sign-approve-password"
                type="password"
                autoComplete="current-password"
                aria-invalid={Boolean(errors.password)}
                {...register("password")}
              />
              {errors.password && <p className="mt-1.5 text-[12px] text-destructive">{errors.password.message}</p>}
            </div>

            <div className="mb-5 text-left">
              <Label htmlFor="sign-approve-otp-0">Authenticator app code (if enabled)</Label>
              <OtpInput value={totp} onChange={(value) => setValue("totp", value)} disabled={isSubmitting} />
              {errors.totp && (
                <p className="-mt-3 mb-3 text-center text-[12px] text-destructive">{errors.totp.message}</p>
              )}
            </div>

            <div className="mb-5 text-left">
              <Checkbox
                id="sign-approve-consent"
                label="I intend to sign this document"
                checked={consent}
                onCheckedChange={(value) => setValue("consent", value)}
                disabled={isSubmitting}
              />
              {errors.consent && <p className="mt-1.5 text-[12px] text-destructive">{errors.consent.message}</p>}
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
                Sign and approve
              </button>
            </div>
          </form>
        </AlertDialogPopup>
      </AlertDialogPortal>
    </AlertDialog>
  )
}
