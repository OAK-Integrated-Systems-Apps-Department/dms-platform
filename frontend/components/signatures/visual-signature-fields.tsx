"use client"

import { useRef, useState } from "react"
import { Loader2, Upload, X } from "lucide-react"

import { Input } from "@/components/ui/input"
import { type VisualSignatureKind } from "@/lib/signatures/api"
import { imageFileToTransparentSignature } from "@/lib/signatures/transparent-background"
import { cn } from "@/lib/utils"

import { SignaturePad } from "./signature-pad"
import { TransparentImagePreview } from "./transparent-image-preview"

interface VisualSignatureFieldsProps {
  idPrefix: string
  kind: VisualSignatureKind
  text: string
  /** The drawn-canvas image - entirely separate from `uploadedImageBase64` so Draw and Upload never show each other's content. */
  drawnImageBase64: string | null
  /** The uploaded-photo image, after background removal - entirely separate from `drawnImageBase64`. */
  uploadedImageBase64: string | null
  onKindChange: (kind: VisualSignatureKind) => void
  onTextChange: (text: string) => void
  onDrawnImageChange: (imageBase64: string | null) => void
  onUploadedImageChange: (imageBase64: string | null) => void
  textError?: string
  drawnImageError?: string
  uploadedImageError?: string
  disabled?: boolean
}

const KINDS: { value: VisualSignatureKind; label: string }[] = [
  { value: "typed", label: "Type" },
  { value: "drawn", label: "Draw" },
  { value: "uploaded", label: "Upload" },
]

/**
 * The three ways a user can represent their cosmetic visual signature - type
 * a name (shown in a script font), draw on a canvas (`SignaturePad`), or
 * upload a photo/scan of a real signature, run through
 * `imageFileToTransparentSignature` so its background doesn't show as an
 * opaque white/gray box wherever the signature is later displayed.
 * `kind` IS which editor is shown - no separate local "which tab" guess,
 * which is the whole reason "drawn" and "uploaded" are different kinds (see
 * `VisualSignatureKind`'s own doc comment): re-opening an already-saved
 * upload shows the upload editor with its image, not the empty drawing
 * canvas.
 *
 * Shared between `SignAndApproveDialog` (the signing ceremony) and
 * `VisualSignatureCard` (setting one up ahead of time from My Profile) so
 * the two don't duplicate this logic.
 */
export function VisualSignatureFields({
  idPrefix,
  kind,
  text,
  drawnImageBase64,
  uploadedImageBase64,
  onKindChange,
  onTextChange,
  onDrawnImageChange,
  onUploadedImageChange,
  textError,
  drawnImageError,
  uploadedImageError,
  disabled,
}: VisualSignatureFieldsProps) {
  const [uploading, setUploading] = useState(false)
  const [uploadError, setUploadError] = useState<string | null>(null)
  const fileInputRef = useRef<HTMLInputElement | null>(null)

  function selectKind(next: VisualSignatureKind) {
    setUploadError(null)
    onKindChange(next)
  }

  async function handleFileChange(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    event.target.value = "" // lets choosing the exact same file again still fire onChange
    if (!file) return
    setUploading(true)
    setUploadError(null)
    try {
      const transparent = await imageFileToTransparentSignature(file)
      onUploadedImageChange(transparent)
    } catch (error) {
      setUploadError(error instanceof Error ? error.message : "Could not process that image.")
    } finally {
      setUploading(false)
    }
  }

  return (
    <div>
      <div className="mb-1.5 flex rounded-md border border-[var(--border-strong)] p-0.5 text-[11.5px] font-semibold">
        {KINDS.map(({ value, label }) => (
          <button
            key={value}
            type="button"
            onClick={() => selectKind(value)}
            disabled={disabled}
            className={cn(
              "flex-1 rounded-[5px] px-2 py-1 disabled:cursor-not-allowed",
              kind === value ? "bg-primary text-primary-foreground" : "text-muted-foreground"
            )}
          >
            {label}
          </button>
        ))}
      </div>

      {kind === "typed" && (
        <>
          <Input
            id={`${idPrefix}-text`}
            placeholder="Type your name"
            value={text}
            onChange={(e) => onTextChange(e.target.value)}
            disabled={disabled}
            aria-invalid={Boolean(textError)}
          />
          {text && (
            <p className="mt-2 rounded-btn border border-[var(--border-strong)] bg-[var(--secondary)] px-3 py-2 text-center text-[26px] font-signature">
              {text}
            </p>
          )}
          {textError && <p className="mt-1.5 text-[12px] text-destructive">{textError}</p>}
        </>
      )}

      {kind === "drawn" && (
        <>
          <SignaturePad value={drawnImageBase64} onChange={onDrawnImageChange} disabled={disabled} />
          {drawnImageError && <p className="mt-1.5 text-[12px] text-destructive">{drawnImageError}</p>}
        </>
      )}

      {kind === "uploaded" && (
        <>
          <p className="mb-1.5 text-[12px] text-muted-foreground">
            A photo or scan of your signature - its background is removed automatically.
          </p>
          <input
            ref={fileInputRef}
            type="file"
            accept="image/png,image/jpeg"
            onChange={handleFileChange}
            disabled={disabled || uploading}
            className="text-[12.5px] file:mr-3 file:rounded-md file:border file:border-[var(--border-strong)] file:bg-card file:px-3 file:py-1.5 file:text-[12px] file:font-semibold"
          />
          {uploading && (
            <p className="mt-1.5 flex items-center gap-1.5 text-[12px] text-muted-foreground">
              <Loader2 size={12} className="animate-spin" />
              Processing…
            </p>
          )}
          {uploadError && <p className="mt-1.5 text-[12px] text-destructive">{uploadError}</p>}
          {!uploading && uploadedImageBase64 && (
            <>
              <TransparentImagePreview imageBase64={uploadedImageBase64} alt="Your uploaded signature" />
              <button
                type="button"
                onClick={() => onUploadedImageChange(null)}
                disabled={disabled}
                className="mt-1.5 flex items-center gap-1 text-[12px] font-semibold text-muted-foreground hover:text-foreground disabled:cursor-not-allowed disabled:opacity-60"
              >
                <X size={12} aria-hidden="true" />
                Remove image
              </button>
            </>
          )}
          {!uploading && !uploadedImageBase64 && uploadedImageError && (
            <p className="mt-1.5 flex items-center gap-1 text-[12px] text-destructive">
              <Upload size={12} aria-hidden="true" />
              {uploadedImageError}
            </p>
          )}
        </>
      )}
    </div>
  )
}
