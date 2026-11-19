"use client"

import { useRouter } from "next/navigation"
import { toast } from "sonner"

import { DocumentPreview, type PreviewFallbackReason } from "@/components/documents/document-preview"
import { friendlyMessage } from "@/lib/api-error"
import { downloadDocumentFile } from "@/lib/documents/api"
import { saveDownloadedFile } from "@/lib/download-file"
import { reportPreviewFallback } from "@/lib/search/presets-api"
import type { SearchItemDto } from "@/lib/search/types"

/**
 * The existing document preview (same panel, same preview security policy for print, download and copy, same
 * download fallback and failure display) opened from a search result. Nothing here re-implements any of that: the
 * panel's own requests re-check access, so a result that stopped being visible shows the usual "not found" state.
 * When the panel falls back (type not supported, rendering failed or timed out, policy blocks it) a counter is bumped
 * on the server through a closed list of reasons; no document id or name is sent.
 */
export function ResultPreview({
  item,
  onClose,
}: {
  item: SearchItemDto
  onClose: () => void
}) {
  const router = useRouter()

  async function download() {
    try {
      saveDownloadedFile(await downloadDocumentFile(item.documentId))
    } catch (error) {
      toast.error(friendlyMessage(error, "Could not download the document."))
    }
  }

  return (
    <DocumentPreview
      key={item.documentId}
      row={{
        documentId: item.documentId,
        title: item.title,
        fileName: item.fileName,
        mimeType: item.mimeType,
        sizeBytes: item.sizeBytes ?? null,
        documentTypeName: item.documentTypeName,
        createdAt: item.createdAt,
      }}
      onOpenChange={(open) => !open && onClose()}
      onDownload={() => void download()}
      onViewDetails={() => router.push(`/documents/${item.documentId}`)}
      onFallback={(reason: PreviewFallbackReason) => reportPreviewFallback(reason)}
    />
  )
}
