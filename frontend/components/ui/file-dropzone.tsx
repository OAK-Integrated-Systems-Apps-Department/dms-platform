"use client"

import { useId, useRef, useState, type DragEvent, type KeyboardEvent, type ReactNode } from "react"
import { Upload } from "lucide-react"

import { cn } from "@/lib/utils"

interface FileDropzoneProps {
  /** Same syntax as `<input accept>`, e.g. ".xlsx" or "image/*,.pdf". Also applied to dropped files. */
  accept?: string
  multiple?: boolean
  disabled?: boolean
  onFiles: (files: File[]) => void
  /** Accessible name for the drop area, e.g. "Upload filled-in template". */
  label?: string
  /** Replaces the default "Drag files here or click to browse" hint. */
  children?: ReactNode
  className?: string
}

/**
 * Drag-and-drop file picker with a click / Enter / Space fallback to the
 * native file dialog. Generic - it only reports the chosen files through
 * `onFiles`; what happens to them (validation, upload) is up to the caller.
 *
 * Files dropped onto the area bypass the native `accept` filter, so it is
 * re-applied here. With `multiple` off, only the first file is reported.
 */
export function FileDropzone({
  accept,
  multiple = false,
  disabled = false,
  onFiles,
  label,
  children,
  className,
}: FileDropzoneProps) {
  const inputRef = useRef<HTMLInputElement>(null)
  // Counts nested dragenter/dragleave pairs so hovering over child
  // elements doesn't flicker the highlight off.
  const dragDepthRef = useRef(0)
  const [dragging, setDragging] = useState(false)
  const hintId = useId()

  function emit(fileList: FileList | null) {
    if (!fileList || disabled) return
    const files = Array.from(fileList).filter((file) => matchesAccept(file, accept))
    if (files.length === 0) return
    onFiles(multiple ? files : files.slice(0, 1))
  }

  function openPicker() {
    if (!disabled) inputRef.current?.click()
  }

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault()
      openPicker()
    }
  }

  function handleDragEnter(event: DragEvent<HTMLDivElement>) {
    event.preventDefault()
    if (disabled) return
    dragDepthRef.current += 1
    setDragging(true)
  }

  function handleDragOver(event: DragEvent<HTMLDivElement>) {
    event.preventDefault()
    event.dataTransfer.dropEffect = disabled ? "none" : "copy"
  }

  function handleDragLeave(event: DragEvent<HTMLDivElement>) {
    event.preventDefault()
    dragDepthRef.current = Math.max(0, dragDepthRef.current - 1)
    if (dragDepthRef.current === 0) setDragging(false)
  }

  function handleDrop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault()
    dragDepthRef.current = 0
    setDragging(false)
    emit(event.dataTransfer.files)
  }

  return (
    <div
      role="button"
      tabIndex={disabled ? -1 : 0}
      aria-disabled={disabled || undefined}
      aria-label={label}
      aria-describedby={hintId}
      data-slot="file-dropzone"
      data-dragging={dragging || undefined}
      onClick={openPicker}
      onKeyDown={handleKeyDown}
      onDragEnter={handleDragEnter}
      onDragOver={handleDragOver}
      onDragLeave={handleDragLeave}
      onDrop={handleDrop}
      className={cn(
        "flex w-full cursor-pointer flex-col items-center justify-center gap-2 rounded-lg border border-dashed border-[var(--border-strong)] bg-card px-6 py-9 text-center transition-colors outline-none",
        "hover:bg-muted/50 focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50",
        "data-[dragging]:border-primary data-[dragging]:bg-primary/5",
        "aria-disabled:cursor-not-allowed aria-disabled:opacity-50 aria-disabled:hover:bg-card",
        className
      )}
    >
      <span className="flex size-10 items-center justify-center rounded-md bg-muted text-muted-foreground">
        <Upload size={18} aria-hidden="true" />
      </span>
      <div id={hintId} className="text-[13px] leading-[1.6] text-muted-foreground">
        {children ?? (
          <>
            <span className="font-semibold text-foreground">
              Drag {multiple ? "files" : "a file"} here
            </span>{" "}
            or click to browse
          </>
        )}
      </div>
      <input
        ref={inputRef}
        type="file"
        accept={accept}
        multiple={multiple}
        disabled={disabled}
        tabIndex={-1}
        aria-hidden="true"
        className="hidden"
        onClick={(event) => event.stopPropagation()}
        onChange={(event) => {
          emit(event.target.files)
          // Reset so picking the same file again still fires onChange.
          event.target.value = ""
        }}
      />
    </div>
  )
}

function matchesAccept(file: File, accept: string | undefined): boolean {
  if (!accept) return true
  const name = file.name.toLowerCase()
  const type = file.type.toLowerCase()
  return accept
    .split(",")
    .map((token) => token.trim().toLowerCase())
    .filter(Boolean)
    .some((token) => {
      if (token.startsWith(".")) return name.endsWith(token)
      if (token.endsWith("/*")) return type.startsWith(token.slice(0, -1))
      return type === token
    })
}
