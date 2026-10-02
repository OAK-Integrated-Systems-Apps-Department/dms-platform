"use client"

import { useEffect, useState } from "react"
import { useQuery } from "@tanstack/react-query"
import { Download, FileQuestion, Loader2 } from "lucide-react"

import { fileKind, FriendlyDate } from "@/components/documents/browser-items"
import { Button } from "@/components/ui/button"
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet"
import { friendlyMessage } from "@/lib/api-error"
import {
  previewDocumentFile,
  type DocumentListItemDto,
} from "@/lib/documents/api"
import { formatBytes } from "@/lib/quarantine/quarantine-badges"
import { cn } from "@/lib/utils"

/** Text files bigger than this are offered as a download instead of shown. */
const MAX_TEXT_BYTES = 2 * 1024 * 1024

type Renderer = "pdf" | "image" | "video" | "audio" | "text"

const TEXT_TYPES = new Set([
  "application/json",
  "application/xml",
  "application/csv",
  "application/x-yaml",
])

/** How the browser can show a file of this type - or null when it can't (Office files, archives…). */
function rendererFor(mimeType: string | null): Renderer | null {
  const type = (mimeType ?? "").toLowerCase()
  if (type === "application/pdf") return "pdf"
  // HEIC/TIFF aren't shown by most browsers.
  if (type.startsWith("image/") && !/heic|heif|tiff/.test(type)) return "image"
  if (type.startsWith("video/")) return "video"
  if (type.startsWith("audio/")) return "audio"
  if (type.startsWith("text/") || TEXT_TYPES.has(type)) return "text"
  return null
}

/**
 * The in-app preview of a document (opened by clicking it): PDFs, images,
 * video, audio and text are shown here; anything else is offered as a
 * download. Showing it is audited as DOCUMENT_VIEWED. The file is only
 * fetched when it can be shown.
 */
export function DocumentPreview({
  row,
  onOpenChange,
  onDownload,
}: {
  row: DocumentListItemDto
  onOpenChange: (open: boolean) => void
  onDownload: () => void
}) {
  const renderer = rendererFor(row.mimeType)
  const kind = fileKind(row.fileName)
  const tooBigForText =
    renderer === "text" && (row.sizeBytes ?? 0) > MAX_TEXT_BYTES

  const file = useQuery({
    queryKey: ["document-preview", row.documentId],
    queryFn: async () => {
      const { blob } = await previewDocumentFile(row.documentId)
      return {
        url: URL.createObjectURL(blob),
        text: renderer === "text" ? await blob.text() : null,
      }
    },
    enabled: renderer !== null && !tooBigForText,
    gcTime: 0,
    staleTime: Infinity,
    retry: false,
  })
  const url = file.data?.url

  // Free the file's memory when the preview closes.
  useEffect(() => {
    return () => {
      if (url) URL.revokeObjectURL(url)
    }
  }, [url])

  return (
    <Sheet open onOpenChange={onOpenChange}>
      <SheetContent
        side="right"
        className="w-full gap-0 data-[side=right]:w-[92vw] data-[side=right]:sm:max-w-[1100px]"
      >
        <SheetHeader className="border-b border-border pr-12">
          <div className="flex items-start gap-3">
            <span
              aria-hidden="true"
              className={cn(
                "flex size-10 shrink-0 items-center justify-center rounded-lg",
                kind.tone
              )}
            >
              <kind.icon size={20} />
            </span>
            <div className="min-w-0 flex-1">
              <SheetTitle className="truncate">{row.title}</SheetTitle>
              <SheetDescription className="truncate">
                {[
                  row.fileName,
                  row.documentTypeName,
                  row.sizeBytes !== null ? formatBytes(row.sizeBytes) : null,
                ]
                  .filter(Boolean)
                  .join(" · ")}
                {" · "}
                <FriendlyDate iso={row.createdAt} />
              </SheetDescription>
            </div>
            <Button size="sm" variant="outline" onClick={onDownload}>
              <Download />
              Download
            </Button>
          </div>
        </SheetHeader>

        <div className="flex min-h-0 flex-1 flex-col bg-muted/30">
          {renderer === null || tooBigForText ? (
            <NoPreview
              reason={
                tooBigForText
                  ? "This file is too large to show here."
                  : `${kind.label} files can't be shown in the browser.`
              }
              onDownload={onDownload}
            />
          ) : file.isPending ? (
            <p className="m-auto inline-flex items-center gap-2 text-[13px] text-muted-foreground">
              <Loader2 size={16} className="animate-spin" /> Opening…
            </p>
          ) : file.isError ? (
            <p className="m-auto max-w-[420px] text-center text-[13px] text-[var(--rust)]">
              {friendlyMessage(file.error, "Could not open the document.")}
            </p>
          ) : (
            <Viewer
              renderer={renderer}
              url={file.data.url}
              text={file.data.text}
              title={row.title}
              onDownload={onDownload}
            />
          )}
        </div>
      </SheetContent>
    </Sheet>
  )
}

function Viewer({
  renderer,
  url,
  text,
  title,
  onDownload,
}: {
  renderer: Renderer
  url: string
  text: string | null
  title: string
  onDownload: () => void
}) {
  const [broken, setBroken] = useState(false)
  if (broken) {
    return (
      <NoPreview
        reason="Your browser can't show this file."
        onDownload={onDownload}
      />
    )
  }
  switch (renderer) {
    case "pdf":
      return (
        <iframe
          src={url}
          title={title}
          className="h-full w-full flex-1 border-0 bg-white"
        />
      )
    case "image":
      return (
        <div className="flex flex-1 items-center justify-center overflow-auto p-6">
          {/* A blob URL of the user's own file - next/image can't optimise it. */}
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            src={url}
            alt={title}
            onError={() => setBroken(true)}
            className="max-h-full max-w-full rounded-md bg-white object-contain shadow-[var(--ledger-shadow)]"
          />
        </div>
      )
    case "video":
      return (
        <div className="flex flex-1 items-center justify-center p-6">
          <video
            src={url}
            controls
            onError={() => setBroken(true)}
            className="max-h-full max-w-full rounded-md"
          />
        </div>
      )
    case "audio":
      return (
        <div className="flex flex-1 items-center justify-center p-6">
          <audio
            src={url}
            controls
            onError={() => setBroken(true)}
            className="w-full max-w-[520px]"
          />
        </div>
      )
    case "text":
      return (
        <pre className="flex-1 overflow-auto bg-card p-5 font-mono text-[12.5px] leading-[1.6] whitespace-pre-wrap">
          {text}
        </pre>
      )
  }
}

function NoPreview({
  reason,
  onDownload,
}: {
  reason: string
  onDownload: () => void
}) {
  return (
    <div className="m-auto flex max-w-[420px] flex-col items-center gap-3 p-6 text-center">
      <span className="flex size-12 items-center justify-center rounded-full bg-muted text-muted-foreground">
        <FileQuestion size={22} />
      </span>
      <p className="text-[14px] font-semibold">No preview available</p>
      <p className="text-[13px] text-muted-foreground">
        {reason} Download it to open it on your computer.
      </p>
      <Button size="sm" onClick={onDownload}>
        <Download />
        Download
      </Button>
    </div>
  )
}
