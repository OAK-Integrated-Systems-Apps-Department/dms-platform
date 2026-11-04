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
import { type DocumentDto } from "@/lib/documents/api"
import { useMyVisualSignature, useSignatureActions } from "@/lib/signatures/use-signatures"
import { signatureErrorMessage } from "@/lib/signatures/signature-errors"
import { cn } from "@/lib/utils"

import { signAndApproveSchema, type SignAndApproveFormValues } from "./schema"
import { DocumentSignaturePlacementStep } from "./document-signature-placement-step"
import { VisualSignatureFields } from "./visual-signature-fields"

interface SignDocumentDialogProps {
  document: DocumentDto
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
 * "Add an e-signature to this document" - the standalone equivalent of
 * `SignAndApproveDialog` (see that component's own doc comment for the full
 * rundown of each step), minus the approval: nothing here approves or
 * decides a workflow task, so there's no stage/instance status to report
 * back and no "approve" language anywhere in the copy. Same three-step shape
 * otherwise - your signature, place it on the document (via
 * `DocumentSignaturePlacementStep`, which also offers "Add signature page"),
 * confirm it's you with your password.
 */
export function SignDocumentDialog({ document, onOpenChange }: SignDocumentDialogProps) {
  const documentId = document.documentId
  const { signStandalone } = useSignatureActions()
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
      await signStandalone.mutateAsync({
        documentId,
        request: {
          comment: values.comment.trim() || null,
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
      toast.success("Document signed.")
      onOpenChange(false)
    } catch (error) {
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
            <AlertDialogTitle>Add your signature to this document?</AlertDialogTitle>
            <AlertDialogDescription>
              <strong className="font-semibold text-foreground">{document.title}</strong>
            </AlertDialogDescription>

            <p className="mb-4 text-center text-[11.5px] font-semibold tracking-[0.04em] text-muted-foreground uppercase">
              Step {step} of 3 -{" "}
              {step === 1 ? "Confirm your signature" : step === 2 ? "Read and place it on the document" : "Confirm it's you"}
            </p>

            {formError && (
              <p className="mb-4 rounded-btn border border-destructive/30 bg-destructive/10 px-3 py-2 text-left text-[12.5px] text-destructive">
                {formError}
              </p>
            )}

            {!savedVisualSignatureFetched && (
              <p className="py-6 text-center text-[13px] text-muted-foreground">Loading…</p>
            )}

            <div className={cn("text-left", (step !== 1 || !savedVisualSignatureFetched) && "hidden")}>
              <div className="mb-4">
                <Label htmlFor="sign-document-comment">Comment (optional)</Label>
                <Textarea
                  id="sign-document-comment"
                  rows={3}
                  maxLength={2000}
                  placeholder="Anything worth noting about this signature…"
                  aria-invalid={Boolean(errors.comment)}
                  {...register("comment")}
                />
                {errors.comment && <p className="mt-1.5 text-[12px] text-destructive">{errors.comment.message}</p>}
              </div>

              <div className="mb-4">
                <Label>Your signature</Label>
                <VisualSignatureFields
                  idPrefix="sign-document-visual"
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
                  id="sign-document-consent"
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

            {step === 2 && (
              <DocumentSignaturePlacementStep
                document={document}
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
                <Label htmlFor="sign-document-password">Password</Label>
                <div className="relative">
                  <Input
                    id="sign-document-password"
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
                  Sign document
                </button>
              </div>
            </div>
          </form>
        </AlertDialogPopup>
      </AlertDialogPortal>
    </AlertDialog>
  )
}
