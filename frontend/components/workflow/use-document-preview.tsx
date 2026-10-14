"use client"

import { useRouter } from "next/navigation"
import { useState, type ReactNode } from "react"
import { toast } from "sonner"

import { DocumentPreview } from "@/components/documents/document-preview"
import { friendlyMessage } from "@/lib/api-error"
import { downloadDocumentFile, downloadDocumentVersion, type DocumentListItemDto } from "@/lib/documents/api"
import { saveDownloadedFile } from "@/lib/download-file"

/** The fields DocumentPreview needs to render and label a file. */
export type PreviewRow = Pick<
  DocumentListItemDto,
  "documentId" | "title" | "fileName" | "mimeType" | "sizeBytes" | "documentTypeName" | "createdAt"
>

/** A pinned version to preview instead of the current one (supporting documents are version-pinned). */
export interface PreviewVersion {
  versionId: string
  label: string
  current: boolean
}

/** Extra options for open(): a pinned version, or a custom byte source (e.g. a supporting document's pinned version). */
export interface PreviewOptions {
  version?: PreviewVersion
  contentSource?: () => Promise<Blob>
  contentKey?: string
}

interface PreviewState extends PreviewOptions {
  row: PreviewRow
}

/**
 * One shared DocumentPreview sheet (the same one My Documents uses) for the
 * workflow UI. `open(row, version?)` shows a file - the current version, or a
 * pinned one for supporting documents - and `element` is rendered once by the
 * caller. Download fetches the matching version; "View details" opens the
 * document page.
 */
export function useDocumentPreview() {
  const router = useRouter()
  const [preview, setPreview] = useState<PreviewState | null>(null)

  async function download() {
    if (!preview) return
    try {
      if (preview.contentSource) {
        // Download the same pinned bytes the viewer shows.
        saveDownloadedFile({ blob: await preview.contentSource(), filename: preview.row.fileName ?? "document" })
      } else {
        saveDownloadedFile(
          preview.version
            ? await downloadDocumentVersion(preview.row.documentId, preview.version.versionId)
            : await downloadDocumentFile(preview.row.documentId)
        )
      }
    } catch (error) {
      toast.error(friendlyMessage(error, "Could not download the document."))
    }
  }

  const element: ReactNode = preview ? (
    <DocumentPreview
      key={`${preview.row.documentId}:${preview.contentKey ?? preview.version?.versionId ?? "current"}`}
      row={preview.row}
      version={preview.version}
      contentSource={preview.contentSource}
      contentKey={preview.contentKey}
      onOpenChange={(open) => !open && setPreview(null)}
      onDownload={() => void download()}
      onViewDetails={() => router.push(`/documents/${preview.row.documentId}`)}
    />
  ) : null

  return {
    open: (row: PreviewRow, opts?: PreviewOptions) => setPreview({ row, ...opts }),
    element,
  }
}
