"use client"

import { useRef } from "react"
import { FolderPlus, Loader2, Upload } from "lucide-react"

interface WelcomeHeaderProps {
  firstName: string
  dateLabel: string
  /** True while the signed-in user's name is still loading — shows a small inline spinner in place of the name rather than a placeholder like "there", or swapping out this whole header (the actions below don't depend on it and stay usable). */
  isLoadingName?: boolean
  /** Called with the files the user picked from the OS file dialog. */
  onUpload?: (files: FileList) => void
  /** Called with a trimmed, non-empty folder name once the user confirms one. */
  /** Opens the new-folder dialog; the button only shows when this is passed (the caller may create folders). */
  onCreateFolder?: () => void
}

const btnGhostClass =
  "flex items-center gap-[7px] rounded-btn border border-[var(--border-strong)] bg-card px-[15px] py-[9px] text-[13px] font-semibold text-foreground"

const btnPrimaryClass =
  "flex items-center gap-[7px] rounded-btn border border-primary bg-primary px-[15px] py-[9px] text-[13px] font-semibold text-primary-foreground"

/** Date eyebrow + greeting + the two primary quick actions. */
export function WelcomeHeader({
  firstName,
  dateLabel,
  isLoadingName,
  onUpload,
  onCreateFolder,
}: WelcomeHeaderProps) {
  const fileInputRef = useRef<HTMLInputElement>(null)

  function handleUploadClick() {
    fileInputRef.current?.click()
  }

  function handleFilesSelected(event: React.ChangeEvent<HTMLInputElement>) {
    const files = event.target.files
    if (files && files.length > 0) {
      onUpload?.(files)
    }
    // Reset so selecting the same file again still fires onChange.
    event.target.value = ""
  }

  return (
    <div className="mb-[22px] flex flex-wrap items-end justify-between gap-3.5">
      <div>
        <div className="mb-1 text-[11.5px] font-semibold tracking-[0.1em] text-primary">
          {dateLabel.toUpperCase()}
        </div>
        <h1 className="flex items-center gap-2.5 font-heading text-[30px] font-semibold tracking-[-0.01em]">
          {isLoadingName ? (
            <>
              Welcome back
              <Loader2
                size={20}
                className="animate-spin text-muted-foreground"
              />
            </>
          ) : (
            `Welcome back, ${firstName}`
          )}
        </h1>
      </div>
      <div className="flex gap-2.5">
        {onCreateFolder && (
          <button
            type="button"
            className={btnGhostClass}
            onClick={onCreateFolder}
          >
            <FolderPlus size={16} />
            Create folder
          </button>
        )}
        {/* Only offered when the page wires it up (the caller may upload). */}
        {onUpload && (
          <button
            type="button"
            className={btnPrimaryClass}
            onClick={handleUploadClick}
          >
            <Upload size={16} />
            Upload documents
          </button>
        )}
        <input
          ref={fileInputRef}
          type="file"
          multiple
          className="hidden"
          onChange={handleFilesSelected}
        />
      </div>
    </div>
  )
}
