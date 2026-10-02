"use client"

import { useState } from "react"
import { ShieldCheck, Upload } from "lucide-react"

import { BulkUploadSheet } from "@/components/documents/bulk-upload-sheet"
import { Button } from "@/components/ui/button"
import { FileDropzone } from "@/components/ui/file-dropzone"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"

const TYPES = [
  "PDF",
  "Word",
  "Excel",
  "PowerPoint",
  "Images",
  "Email",
  "Visio",
  "DWG",
  "Text / CSV",
]

/**
 * Bulk upload without a template: many files at once, all as one document
 * type and into one folder (or the top level), no metadata. Each file goes
 * through the normal malware-scanned upload on its own (the same panel as
 * the dashboard's Upload documents button). Uploading needs
 * docm.documents.create.
 */
export function QuickUploadCard() {
  const { hasPermission } = usePermissions()
  const [sheet, setSheet] = useState<{ key: number; files: File[] } | null>(
    null
  )
  const canUpload = hasPermission(PermissionCodes.DOCM_DOCUMENTS_CREATE)

  if (!canUpload) {
    return (
      <p className="rounded-xl border border-border bg-card px-5 py-8 text-center text-[13px] text-muted-foreground shadow-[var(--ledger-shadow)]">
        You don&apos;t have permission to upload documents.
      </p>
    )
  }

  return (
    <section
      aria-label="Quick upload"
      className="rounded-xl border border-border bg-card p-5 shadow-[var(--ledger-shadow)] sm:p-6"
    >
      <FileDropzone
        multiple
        onFiles={(files) => setSheet({ key: Date.now(), files })}
        label="Choose files to upload"
        className="py-10"
      >
        <span className="flex flex-col items-center gap-3">
          <span className="text-[15px]">
            <span className="font-semibold text-foreground">
              Drag files here
            </span>{" "}
            or click to browse
          </span>
          <span className="flex max-w-[560px] flex-wrap justify-center gap-1.5">
            {TYPES.map((type) => (
              <span
                key={type}
                className="rounded-full border border-border bg-card px-2 py-0.5 text-[11.5px] text-muted-foreground"
              >
                {type}
              </span>
            ))}
          </span>
          <span className="text-[12px]">Up to 100 MB each</span>
        </span>
      </FileDropzone>
      <div className="mt-4 flex flex-wrap items-center justify-between gap-3">
        <p className="inline-flex items-center gap-2 text-[12.5px] text-muted-foreground">
          <ShieldCheck size={15} className="text-primary" aria-hidden="true" />
          You choose the document type and folder next. Every file is
          virus-scanned before it becomes a document.
        </p>
        <Button
          variant="outline"
          size="sm"
          onClick={() => setSheet({ key: Date.now(), files: [] })}
        >
          <Upload />
          Open the upload panel
        </Button>
      </div>
      {sheet && (
        <BulkUploadSheet
          key={sheet.key}
          open
          onOpenChange={(open) => !open && setSheet(null)}
          initialFiles={sheet.files}
        />
      )}
    </section>
  )
}
