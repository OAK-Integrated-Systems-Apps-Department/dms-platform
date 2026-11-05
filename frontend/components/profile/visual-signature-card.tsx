"use client"

import { useState } from "react"
import { Loader2, PenLine } from "lucide-react"
import { toast } from "sonner"

import { LoadingState } from "@/components/ui/loading-state"
import { ConfirmPasswordDialog } from "@/components/signatures/confirm-password-dialog"
import { VisualSignatureFields } from "@/components/signatures/visual-signature-fields"
import { useMyVisualSignature, useSignatureActions } from "@/lib/signatures/use-signatures"
import { type VisualSignatureDto, type VisualSignatureKind } from "@/lib/signatures/api"

/**
 * Lets a user set up their e-signature (1.4) ahead of time, from My Profile,
 * rather than only being asked for one the first time they approve a
 * signature-required stage. Purely cosmetic - see
 * `UserVisualSignatureService`'s own doc comment on the backend: this is
 * never part of what a real signature cryptographically verifies, and
 * setting one here does nothing on its own - it only pre-fills (and stays
 * editable in) `SignAndApproveDialog` next time they actually sign something.
 */
export function VisualSignatureCard() {
  const { data: saved, isLoading } = useMyVisualSignature()

  if (isLoading) {
    return <LoadingState message="Loading…" />
  }

  // Keyed on whether a saved signature exists (not its content) so the editor below initializes its
  // local state straight from `saved` with no load-triggered effect/setState - it only remounts on the
  // loading -> loaded transition, never again while the user is editing (a `onSuccess` refetch after
  // Save would otherwise re-key and wipe an in-progress edit).
  return <VisualSignatureEditor key={saved ? "saved" : "unset"} saved={saved ?? null} />
}

function VisualSignatureEditor({ saved }: { saved: VisualSignatureDto | null }) {
  const { saveVisualSignature, isSavingVisualSignature } = useSignatureActions()
  const [kind, setKind] = useState<VisualSignatureKind>(saved?.kind ?? "typed")
  const [typedText, setTypedText] = useState(saved?.typedText ?? "")
  // Separate slots for drawn vs uploaded, same reasoning as SignAndApproveDialog's schema - switching
  // tabs must never show one editor's image inside the other.
  const [drawnImageBase64, setDrawnImageBase64] = useState<string | null>(
    saved?.kind === "drawn" ? saved.imageBase64 : null
  )
  const [uploadedImageBase64, setUploadedImageBase64] = useState<string | null>(
    saved?.kind === "uploaded" ? saved.imageBase64 : null
  )
  const [dirty, setDirty] = useState(false)
  const [confirming, setConfirming] = useState(false)

  const currentImage = kind === "drawn" ? drawnImageBase64 : kind === "uploaded" ? uploadedImageBase64 : null
  const canSave = kind === "typed" ? typedText.trim().length > 0 : Boolean(currentImage)

  async function handleConfirm(password: string) {
    // Left to throw on failure - ConfirmPasswordDialog catches it, shows it inline, and stays open.
    await saveVisualSignature({
      visualSignatureKind: kind,
      visualSignatureText: kind === "typed" ? typedText.trim() : null,
      visualSignatureImageBase64: currentImage,
      password,
    })
    setDirty(false)
    setConfirming(false)
    toast.success("Signature saved.")
  }

  return (
    <div
      id="e-signature"
      className="scroll-mt-6 rounded-lg border border-border bg-card px-[30px] py-[26px] shadow-[var(--ledger-shadow)]"
    >
      <div className="mb-1 flex items-center gap-2">
        <PenLine size={15} className="text-primary" aria-hidden="true" />
        <h2 className="font-heading text-[15px] font-semibold">E-signature</h2>
      </div>
      <p className="mb-1 text-[12.5px] text-muted-foreground">
        How your name is shown next to a document you&apos;ve digitally signed. This is cosmetic only - signing
        still requires your password every time.
      </p>
      <p className="mb-4 text-[12px] font-semibold text-muted-foreground">{saved ? "Saved" : "Not set up yet"}</p>

      <VisualSignatureFields
        idPrefix="profile-visual-signature"
        kind={kind}
        text={typedText}
        drawnImageBase64={drawnImageBase64}
        uploadedImageBase64={uploadedImageBase64}
        onKindChange={(k) => {
          setKind(k)
          setDirty(true)
        }}
        onTextChange={(t) => {
          setTypedText(t)
          setDirty(true)
        }}
        onDrawnImageChange={(image) => {
          setDrawnImageBase64(image)
          setDirty(true)
        }}
        onUploadedImageChange={(image) => {
          setUploadedImageBase64(image)
          setDirty(true)
        }}
      />

      <button
        type="button"
        onClick={() => setConfirming(true)}
        disabled={!canSave || !dirty || isSavingVisualSignature}
        className="mt-4 flex items-center gap-1.5 border border-primary bg-primary text-primary-foreground disabled:cursor-not-allowed disabled:opacity-60 h-[38px] rounded-btn px-4 text-[13px] font-semibold justify-center"
      >
        {isSavingVisualSignature && <Loader2 size={13} className="animate-spin" />}
        Save signature
      </button>

      {confirming && (
        <ConfirmPasswordDialog
          title="Confirm it's you"
          description="Enter your password to save this change to your signature."
          onConfirm={handleConfirm}
          onOpenChange={setConfirming}
        />
      )}
    </div>
  )
}
