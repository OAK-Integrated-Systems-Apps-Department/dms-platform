"use client"

import { useEffect, useState } from "react"
import { zodResolver } from "@hookform/resolvers/zod"
import { useForm } from "react-hook-form"
import { Eye, EyeOff, Loader2, PenLine } from "lucide-react"
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
import { useMyVisualSignature, useSignatureActions } from "@/lib/signatures/use-signatures"
import { signatureErrorMessage } from "@/lib/signatures/signature-errors"
import { INSTANCE_STATUS_LABELS } from "@/lib/workflow/workflow-badges"
import { cn } from "@/lib/utils"

import { signAndApproveSchema, type SignAndApproveFormValues } from "./schema"
import { SignaturePlacementStep } from "./signature-placement-step"
import { VisualSignatureFields } from "./visual-signature-fields"

interface SignAndApproveDialogProps {
  taskId: string
  documentTitle: string
  /** e.g. "Stage 2 of 3: Legal review". */
  stageLabel: string
  versionChanged?: boolean
  onOpenChange: (open: boolean) => void
}

const STEP_1_FIELDS = [
  "comment",
  "consent",
  "visualKind",
  "visualText",
  "visualDrawnImageBase64",
  "visualUploadedImageBase64",
] as const

/**
 * 1.4 Digital Signature Integration: approving a stage that
 * `requires_signature` opens this instead of `TaskActionDialog`, as a
 * three-step wizard:
 *   1. Confirm your signature - the cosmetic visual mark (typed name in a
 *      script font, or drawn/uploaded via `VisualSignatureFields`; pre-filled
 *      from `useMyVisualSignature` if the signer has a saved one, editable
 *      here, and saved as their new default on a successful sign - see
 *      `UserVisualSignatureService`'s own doc comment on the backend), a
 *      comment, and the explicit consent checkbox. ALWAYS shown, even with a
 *      saved default - an earlier version of this dialog skipped straight to
 *      step 2 in that case, which meant consent was never actually ticked
 *      and "Sign and approve" could fail validation silently at step 3 with
 *      no visible error. Showing this step every time is what makes that
 *      structurally impossible now, not just visually clearer.
 *   2. Read and place it on the document - `SignaturePlacementStep` renders
 *      the real page large enough to actually read, and lets the signer drag
 *      that same mark onto it, burning it into a NEW document version BEFORE
 *      anything is signed (see that component's, and the backend
 *      `SignaturePlacementService`'s, own doc comments for why this can't be
 *      folded into the sign-and-approve transaction itself). Skipped
 *      automatically for a file type that can't be stamped.
 *   3. Confirm it's you - your password. This step-up re-authentication is
 *      what the real ECDSA signature actually depends on (decision 2 from
 *      the original spec) - the visual mark is cosmetic and verifies nothing
 *      on its own, which is exactly why it's kept separate rather than
 *      presented as if it alone were "the signature." No OTP field here -
 *      nobody in this deployment has MFA enabled yet; see the schema's own
 *      doc comment for what happens if that changes. Clicking Back from here
 *      clears the password (never silently reused) - reviewing the placement
 *      again means re-proving it's still you before signing.
 * <p>
 * On failure the dialog drops back to step 3 with everything from steps 1-2
 * kept exactly as entered - only `password` is cleared, never resubmitted or
 * shown again. Never logged (see `SignAndApproveRequestDto`'s own
 * `toString`-equivalent note on the backend).
 */
export function SignAndApproveDialog({
  taskId,
  documentTitle,
  stageLabel,
  versionChanged,
  onOpenChange,
}: SignAndApproveDialogProps) {
  const { signAndApprove } = useSignatureActions()
  const { data: savedVisualSignature, isFetched: savedVisualSignatureFetched } = useMyVisualSignature()
  const [step, setStep] = useState<1 | 2 | 3>(1)
  const [formError, setFormError] = useState<string | null>(null)
  const [showPassword, setShowPassword] = useState(false)

  const {
    register,
    handleSubmit,
    watch,
    setValue,
    trigger,
    formState: { errors, isSubmitting },
  } = useForm<SignAndApproveFormValues>({
    resolver: zodResolver(signAndApproveSchema),
    defaultValues: {
      comment: "",
      password: "",
      consent: false,
      visualKind: "typed",
      visualText: "",
      visualDrawnImageBase64: null,
      visualUploadedImageBase64: null,
    },
  })
  const consent = watch("consent")
  const visualKind = watch("visualKind")
  const visualText = watch("visualText")
  const visualDrawnImageBase64 = watch("visualDrawnImageBase64")
  const visualUploadedImageBase64 = watch("visualUploadedImageBase64")

  // Pre-fills from the signer's saved visual signature the first time it loads - still editable
  // afterward, and never overwrites something they've already started typing/drawing/uploading in
  // this dialog. Only the field matching the saved kind is set - drawn and uploaded each have their
  // own slot (see schema.ts's own doc comment) so this never seeds the other editor's content too.
  useEffect(() => {
    if (!savedVisualSignature) return
    setValue("visualKind", savedVisualSignature.kind)
    if (savedVisualSignature.kind === "typed") {
      setValue("visualText", savedVisualSignature.typedText ?? "")
    } else if (savedVisualSignature.kind === "drawn") {
      setValue("visualDrawnImageBase64", savedVisualSignature.imageBase64)
    } else {
      setValue("visualUploadedImageBase64", savedVisualSignature.imageBase64)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [savedVisualSignature])

  async function goToStep2() {
    const valid = await trigger(STEP_1_FIELDS)
    if (valid) setStep(2)
  }

  const currentImageBase64 =
    visualKind === "drawn" ? visualDrawnImageBase64 : visualKind === "uploaded" ? visualUploadedImageBase64 : null

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
          totp: null,
          visualSignatureKind: values.visualKind,
          visualSignatureText: values.visualKind === "typed" ? values.visualText.trim() : null,
          visualSignatureImageBase64:
            values.visualKind === "drawn"
              ? values.visualDrawnImageBase64
              : values.visualKind === "uploaded"
                ? values.visualUploadedImageBase64
                : null,
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
      // Never resubmit or redisplay a rejected password - clear it and let the signer retype it on
      // step 2; everything from step 1 (comment, signature, consent) is kept as-is.
      setValue("password", "")
      setFormError(signatureErrorMessage(error))
    }
  }

  /** Reviewing the placement again means re-proving it's still you before the final submit. */
  function backToPlacement() {
    setValue("password", "")
    setStep(2)
  }

  return (
    <AlertDialog open onOpenChange={onOpenChange}>
      <AlertDialogPortal>
        <AlertDialogPopup className={cn(step === 2 ? "sm:max-w-4xl" : "sm:max-w-xl")}>
          <form onSubmit={handleSubmit(submit)}>
            <span className="mx-auto mb-4 flex size-11 items-center justify-center rounded-btn bg-primary/10 text-primary">
              <PenLine size={20} aria-hidden="true" />
            </span>
            <AlertDialogTitle>Sign and approve this document?</AlertDialogTitle>
            <AlertDialogDescription>
              <strong className="font-semibold text-foreground">{documentTitle}</strong>
              <br />
              <span className="text-[13.5px]">{stageLabel}</span>
            </AlertDialogDescription>

            <p className="mb-4 text-center text-[11.5px] font-semibold tracking-[0.04em] text-muted-foreground uppercase">
              Step {step} of 3 -{" "}
              {step === 1 ? "Confirm your signature" : step === 2 ? "Read and place it on the document" : "Confirm it's you"}
            </p>

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

            {!savedVisualSignatureFetched && (
              <p className="py-6 text-center text-[13px] text-muted-foreground">Loading…</p>
            )}

            {/* Step 1 fields stay mounted (hidden) rather than unmounted on step 2, so react-hook-form
                keeps their values and validation state across the step change with no extra wiring. */}
            <div className={cn("text-left", (step !== 1 || !savedVisualSignatureFetched) && "hidden")}>
              <div className="mb-4">
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

              <div className="mb-4">
                <Label>Your signature</Label>
                <VisualSignatureFields
                  idPrefix="sign-approve-visual"
                  kind={visualKind}
                  text={visualText}
                  drawnImageBase64={visualDrawnImageBase64}
                  uploadedImageBase64={visualUploadedImageBase64}
                  onKindChange={(k) => setValue("visualKind", k)}
                  onTextChange={(t) => setValue("visualText", t)}
                  onDrawnImageChange={(image) => setValue("visualDrawnImageBase64", image)}
                  onUploadedImageChange={(image) => setValue("visualUploadedImageBase64", image)}
                  textError={errors.visualText?.message}
                  drawnImageError={errors.visualDrawnImageBase64?.message}
                  uploadedImageError={errors.visualUploadedImageBase64?.message}
                  disabled={isSubmitting}
                />
              </div>

              <div className="mb-5">
                <Checkbox
                  id="sign-approve-consent"
                  label="I intend to sign this document"
                  checked={consent}
                  onCheckedChange={(value) => setValue("consent", value)}
                />
                {errors.consent && <p className="mt-1.5 text-[12px] text-destructive">{errors.consent.message}</p>}
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
                  onClick={goToStep2}
                  className="border border-primary bg-primary text-primary-foreground h-[38px] rounded-btn px-4 text-[13px] font-semibold inline-flex items-center justify-center"
                >
                  Next
                </button>
              </div>
            </div>

            {/* Mounted only while on step 2 (unlike steps 1/3, which stay mounted-but-hidden to keep their
                react-hook-form fields) - it holds no form fields of its own and makes its own API calls, so
                there's nothing to preserve by keeping it alive off-screen. */}
            {step === 2 && (
              <SignaturePlacementStep
                taskId={taskId}
                visualSignatureKind={visualKind}
                visualSignatureText={visualText}
                visualSignatureImageBase64={currentImageBase64}
                onBack={() => setStep(1)}
                onDone={() => setStep(3)}
              />
            )}

            <div className={cn("text-left", step !== 3 && "hidden")}>
              <p className="mb-4 text-[12.5px] text-muted-foreground">
                Re-enter your password to confirm this is really you, right now - this is what the signature itself
                depends on, separate from the signature mark on the previous steps.
              </p>

              <div className="mb-4">
                <Label htmlFor="sign-approve-password">Password</Label>
                <div className="relative">
                  <Input
                    id="sign-approve-password"
                    type={showPassword ? "text" : "password"}
                    autoComplete="current-password"
                    aria-invalid={Boolean(errors.password)}
                    className="pr-10"
                    {...register("password")}
                  />
                  <button
                    type="button"
                    onClick={() => setShowPassword((prev) => !prev)}
                    tabIndex={-1}
                    aria-label={showPassword ? "Hide password" : "Show password"}
                    aria-pressed={showPassword}
                    className="absolute top-1/2 right-3 -translate-y-1/2 text-muted-foreground transition-colors hover:text-foreground"
                  >
                    {showPassword ? <EyeOff size={16} /> : <Eye size={16} />}
                  </button>
                </div>
                {errors.password && <p className="mt-1.5 text-[12px] text-destructive">{errors.password.message}</p>}
              </div>

              <div className="flex items-center justify-center gap-2.5">
                <button
                  type="button"
                  onClick={backToPlacement}
                  disabled={isSubmitting}
                  className="border border-[var(--border-strong)] bg-card font-semibold text-foreground disabled:cursor-not-allowed disabled:opacity-60 h-[38px] rounded-btn px-4 text-[13px] inline-flex items-center justify-center"
                >
                  Back
                </button>
                <button
                  type="submit"
                  disabled={isSubmitting}
                  className="flex items-center gap-1.5 border border-primary bg-primary text-primary-foreground disabled:cursor-not-allowed disabled:opacity-60 h-[38px] rounded-btn px-4 text-[13px] font-semibold"
                >
                  {isSubmitting && <Loader2 size={14} className="animate-spin" />}
                  Sign and approve
                </button>
              </div>
            </div>
          </form>
        </AlertDialogPopup>
      </AlertDialogPortal>
    </AlertDialog>
  )
}
