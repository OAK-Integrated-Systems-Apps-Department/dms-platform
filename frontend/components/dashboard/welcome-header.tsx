"use client"

import { useRef } from "react"
import { FolderPlus, Upload } from "lucide-react"

interface WelcomeHeaderProps {
  firstName: string
  dateLabel: string
  /** Called with the files the user picked from the OS file dialog. */
  onUpload?: (files: FileList) => void
  /** Called with a trimmed, non-empty folder name once the user confirms one. */
  onCreateFolder?: (name: string) => void
}

const btnGhostClass =
  "flex items-center gap-[7px] rounded-[9px] border border-[var(--border-strong)] bg-card px-[15px] py-[9px] text-[13px] font-semibold text-foreground"

const btnPrimaryClass =
  "flex items-center gap-[7px] rounded-[9px] border border-primary bg-primary px-[15px] py-[9px] text-[13px] font-semibold text-primary-foreground"

/** Date eyebrow + greeting + the two primary quick actions. */
export function WelcomeHeader({ firstName, dateLabel, onUpload, onCreateFolder }: WelcomeHeaderProps) {
  const fileInputRef = useRef<HTMLInputElement>(null)

  function handleCreateFolderClick() {
    const name = window.prompt("Name this folder")
    const trimmed = name?.trim()
    if (trimmed) {
      onCreateFolder?.(trimmed)
    }
  }

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
        <h1 className="font-heading text-[30px] font-semibold tracking-[-0.01em]">
          Welcome back, {firstName}
        </h1>
      </div>
      <div className="flex gap-2.5">
        <button type="button" className={btnGhostClass} onClick={handleCreateFolderClick}>
          <FolderPlus size={16} />
          Create folder
        </button>
        <button type="button" className={btnPrimaryClass} onClick={handleUploadClick}>
          <Upload size={16} />
          Upload document
        </button>
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