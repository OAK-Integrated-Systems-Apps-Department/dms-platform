"use client"

import { LoadFailed } from "@/components/ui/load-failed"
import { PinButton } from "@/components/pins/pin-button"
import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type CSSProperties,
  type PointerEvent as ReactPointerEvent,
} from "react"
import { useQuery } from "@tanstack/react-query"
import {
  Download,
  Droplet,
  FileArchive,
  FileQuestion,
  FileSearch,
  Loader2,
  ZoomIn,
  ZoomOut,
} from "lucide-react"

import { fileKind, FriendlyDate } from "@/components/documents/browser-items"
import { RelationshipsLink } from "@/components/relationships/relationships-link"
import { Button } from "@/components/ui/button"
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet"
import {
  previewConvertedPdf,
  previewDocumentFile,
  previewVersionFile,
  previewVersionPdf,
  type DocumentListItemDto,
} from "@/lib/documents/api"
import {
  EmailViewer,
  HeicViewer,
  SheetViewer,
  TiffViewer,
  ZoomableImage,
} from "@/components/documents/preview-viewers"
import { SlideViewer } from "@/components/documents/slide-viewer"
import { formatBytes } from "@/lib/quarantine/quarantine-badges"
import { cn } from "@/lib/utils"

/** Text files bigger than this are offered as a download instead of shown. */
const MAX_TEXT_BYTES = 2 * 1024 * 1024

type Renderer =
  | "pdf"
  | "image"
  | "video"
  | "audio"
  | "text"
  | "docx"
  | "converted"
  | "slides"
  | "sheet"
  | "email"
  | "heic"
  | "tiff"

/** By file extension - what the file is, whatever its MIME type says. */
const BY_EXTENSION: Record<string, Renderer> = {
  pdf: "pdf",
  docx: "docx",
  // Converted to PDF on the server (DocumentRenditionService) - presentations
  // are then shown slide by slide, the rest as the PDF.
  doc: "converted",
  ppt: "slides",
  pptx: "slides",
  odt: "converted",
  odp: "slides",
  odg: "converted",
  rtf: "converted",
  vsd: "converted",
  vsdx: "converted",
  xlsx: "sheet",
  xls: "sheet",
  ods: "sheet",
  csv: "sheet",
  tsv: "sheet",
  eml: "email",
  msg: "email",
  heic: "heic",
  heif: "heic",
  tif: "tiff",
  tiff: "tiff",
  png: "image",
  jpg: "image",
  jpeg: "image",
  gif: "image",
  bmp: "image",
  webp: "image",
  txt: "text",
  md: "text",
  json: "text",
  xml: "text",
}

function extensionOf(fileName: string | null): string {
  const name = (fileName ?? "").toLowerCase()
  const dot = name.lastIndexOf(".")
  return dot < 0 ? "" : name.slice(dot + 1)
}

/**
 * Zoom steps - 1 is "fitted to the panel". PDFs (and the Office files shown
 * as PDF) keep the browser's own PDF viewer, which has its own zoom.
 */
const ZOOM_STEPS = [
  0.25, 0.33, 0.5, 0.67, 0.75, 0.9, 1, 1.1, 1.25, 1.5, 1.75, 2, 2.5, 3, 4,
]
const ZOOMABLE = new Set<Renderer>([
  "image",
  "heic",
  "tiff",
  "docx",
  "slides",
  "sheet",
  "email",
  "text",
])

function zoomStep(zoom: number, direction: 1 | -1): number {
  if (direction > 0)
    return ZOOM_STEPS.find((step) => step > zoom + 0.001) ?? zoom
  return [...ZOOM_STEPS].reverse().find((step) => step < zoom - 0.001) ?? zoom
}

/** The panel's width: dragged by its left edge, remembered in this browser. */
const WIDTH_KEY = "dms.preview.width"
const MIN_WIDTH = 380
const DEFAULT_WIDTH = 1100

const TEXT_TYPES = new Set([
  "application/json",
  "application/xml",
  "application/csv",
  "application/x-yaml",
])

/** How a file of this type is shown - or null when it can't be (AutoCAD .dwg). */
function rendererFor(
  mimeType: string | null,
  fileName: string | null
): Renderer | null {
  const byName = BY_EXTENSION[extensionOf(fileName)]
  if (byName) return byName
  const type = (mimeType ?? "").toLowerCase()
  if (type === "application/pdf") return "pdf"
  if (type.startsWith("image/") && !/heic|heif|tiff|dwg/.test(type))
    return "image"
  if (type.startsWith("video/")) return "video"
  if (type.startsWith("audio/")) return "audio"
  if (type.startsWith("text/") || TEXT_TYPES.has(type)) return "text"
  return null
}

/** Why a file can't be shown, in words. */
function noPreviewReason(fileName: string | null, label: string): string {
  if (extensionOf(fileName) === "dwg")
    return "AutoCAD drawings (.dwg) can't be shown in the browser - open it in AutoCAD or a DWG viewer."
  if (extensionOf(fileName) === "zip")
    return "ZIP archives are kept exactly as uploaded - nothing in them is opened or unpacked here. Every file inside was checked for viruses when it was uploaded."
  return `${label} files can't be shown in the browser.`
}

function maxWidth(): number {
  return Math.max(MIN_WIDTH, window.innerWidth - 40)
}

function clampWidth(width: number): number {
  return Math.round(Math.min(maxWidth(), Math.max(MIN_WIDTH, width)))
}

/** The panel's width, kept between previews; starts at the default (fitted to the window). */
function useStoredWidth(): [number, (width: number) => void] {
  const [width, setWidth] = useState(() => {
    if (typeof window === "undefined") return DEFAULT_WIDTH
    let stored: number | null = null
    try {
      stored = Number(window.localStorage.getItem(WIDTH_KEY)) || null
    } catch {
      // storage blocked - just use the default
    }
    return clampWidth(
      stored ?? Math.min(DEFAULT_WIDTH, window.innerWidth * 0.92)
    )
  })
  function update(next: number) {
    const clamped = clampWidth(next)
    setWidth(clamped)
    try {
      window.localStorage.setItem(WIDTH_KEY, String(clamped))
    } catch {
      // storage blocked - the width still applies until the page reloads
    }
  }
  return [width, update]
}

/**
 * The in-app preview of a document (opened by clicking it). Every type that
 * can be uploaded is shown here except AutoCAD .dwg: PDF, Word, Excel and
 * other spreadsheets, PowerPoint (as slides), OpenDocument, RTF, Visio
 * (converted to PDF on the server), email (.eml/.msg), images (HEIC and TIFF too), text. Showing it is audited as DOCUMENT_VIEWED. The file
 * is only fetched when it can be shown. The panel is resized sideways by
 * dragging its left edge (or with the arrow keys on that edge).
 */
export function DocumentPreview({
  row,
  version,
  contentSource,
  contentKey,
  onOpenChange,
  onDownload,
  onViewDetails,
  onWatermark,
  watermarkDisabled,
  watermarkHint,
}: {
  row: Pick<
    DocumentListItemDto,
    | "documentId"
    | "title"
    | "fileName"
    | "mimeType"
    | "sizeBytes"
    | "documentTypeName"
    | "createdAt"
  >
  /** An older version from the history; absent = the current version. */
  version?: { versionId: string; label: string; current: boolean }
  /**
   * Fetch the raw bytes yourself instead of the documents/* endpoints - e.g. a workflow supporting
   * document's pinned version. Server-side PDF conversion and slide rendering aren't available this
   * way, so Office files that need converting show as "can't be shown here" with a download.
   */
  contentSource?: () => Promise<Blob>
  /** Cache key for contentSource (e.g. the supporting-document id), so two previews of the same document don't collide. */
  contentKey?: string
  onOpenChange: (open: boolean) => void
  onDownload: () => void
  /** Opens the document's own page (/documents/{id}) - omitted where there is nowhere to go. */
  onViewDetails?: () => void
  /**
   * Opens the watermark dialog. Absent = no button at all, which is how
   * the caller expresses "the feature is off here, or this user can't view
   * documents" - the two cases where offering it would be a lie.
   */
  onWatermark?: () => void
  /** Shown but not usable - an unsupported file type, or the renderer being unavailable. */
  watermarkDisabled?: boolean
  /** Why it's disabled. */
  watermarkHint?: string
}) {
  const nativeRenderer = rendererFor(row.mimeType, row.fileName)
  // Pinned/external content comes as raw bytes only - the server can't convert or slice it here.
  const renderer =
    contentSource && (nativeRenderer === "converted" || nativeRenderer === "slides")
      ? null
      : nativeRenderer
  const kind = fileKind(row.fileName)
  const [width, setWidth] = useStoredWidth()
  const [dragging, setDragging] = useState(false)
  const [zoom, setZoom] = useState(1)
  const zoomable = renderer !== null && ZOOMABLE.has(renderer)
  const content = useRef<HTMLDivElement>(null)
  const tooBigForText =
    renderer === "text" && (row.sizeBytes ?? 0) > MAX_TEXT_BYTES

  const file = useQuery({
    queryKey: [
      "document-preview",
      row.documentId,
      contentKey ?? version?.versionId ?? "current",
    ],
    queryFn: async () => {
      // Office files the browser can't read come as a PDF made on the server.
      const blob = contentSource
        ? await contentSource()
        : version
          ? renderer === "converted"
            ? await previewVersionPdf(row.documentId, version.versionId)
            : await previewVersionFile(row.documentId, version.versionId)
          : renderer === "converted"
            ? await previewConvertedPdf(row.documentId)
            : (await previewDocumentFile(row.documentId)).blob
      return {
        url: URL.createObjectURL(blob),
        text: renderer === "text" ? await blob.text() : null,
        blob,
      }
    },
    // A slide show fetches its own slides, one at a time.
    enabled: renderer !== null && renderer !== "slides" && !tooBigForText,
    gcTime: 0,
    staleTime: Infinity,
    retry: false,
  })
  const url = file.data?.url

  // Ctrl (or Cmd) + mouse wheel / trackpad pinch zooms the file, not the whole page.
  useEffect(() => {
    const element = content.current
    if (!element || !zoomable) return
    function onWheel(event: WheelEvent) {
      if (!event.ctrlKey && !event.metaKey) return
      event.preventDefault()
      setZoom((current) => zoomStep(current, event.deltaY < 0 ? 1 : -1))
    }
    element.addEventListener("wheel", onWheel, { passive: false })
    return () => element.removeEventListener("wheel", onWheel)
  }, [zoomable, file.isSuccess])

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
        style={{ "--preview-width": `${width}px` } as CSSProperties}
        className="w-full gap-0 data-[side=right]:w-full data-[side=right]:sm:w-[var(--preview-width)] data-[side=right]:sm:max-w-[calc(100vw-40px)]"
      >
        <ResizeHandle
          width={width}
          onResize={setWidth}
          onDragging={setDragging}
        />
        {/* While dragging, the PDF/Word view mustn't swallow the pointer. */}
        {dragging && <div className="fixed inset-0 z-50 cursor-col-resize" />}
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
              <SheetTitle className="flex min-w-0 items-center gap-2">
                <span className="truncate">{row.title}</span>
                {version && (
                  <span
                    className={cn(
                      "shrink-0 font-mono inline-flex h-6 items-center justify-center gap-1 rounded-btn px-2.5 text-[12px] font-semibold whitespace-nowrap",
                      version.current
                        ? "bg-primary/10 text-primary"
                        : "bg-[var(--gold-tint)] text-foreground"
                    )}
                  >
                    v{version.label}
                    {version.current ? " · current" : " · older version"}
                  </span>
                )}
              </SheetTitle>
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
            <PinButton row={row} />
            {zoomable && (renderer === "slides" || file.isSuccess) && (
              <div
                role="group"
                aria-label="Zoom"
                className="flex items-center rounded-md border border-border"
              >
                <Button
                  size="icon-sm"
                  variant="ghost"
                  aria-label="Zoom out"
                  title="Zoom out (Ctrl + scroll)"
                  disabled={zoom <= ZOOM_STEPS[0]}
                  onClick={() => setZoom((z) => zoomStep(z, -1))}
                >
                  <ZoomOut />
                </Button>
                <button
                  type="button"
                  onClick={() => setZoom(1)}
                  title="Fit to the panel"
                  aria-label={`Zoom ${Math.round(zoom * 100)}% - reset to fit`}
                  className="min-w-12 px-1 text-[12px] font-medium text-muted-foreground tabular-nums hover:text-foreground"
                >
                  {Math.round(zoom * 100)}%
                </button>
                <Button
                  size="icon-sm"
                  variant="ghost"
                  aria-label="Zoom in"
                  title="Zoom in (Ctrl + scroll)"
                  disabled={zoom >= ZOOM_STEPS[ZOOM_STEPS.length - 1]}
                  onClick={() => setZoom((z) => zoomStep(z, 1))}
                >
                  <ZoomIn />
                </Button>
              </div>
            )}
            <RelationshipsLink documentId={row.documentId} />
            {onViewDetails && (
              <Button size="sm" variant="outline" onClick={onViewDetails}>
                <FileSearch />
                View details
              </Button>
            )}
            {onWatermark && (
              <Button
                size="sm"
                variant="outline"
                // aria-disabled rather than disabled so the reason stays
                // reachable: a disabled button takes no focus and shows no
                // title, which would leave a keyboard user with a greyed
                // control and no explanation.
                aria-disabled={watermarkDisabled}
                title={watermarkHint}
                onClick={() => {
                  if (watermarkDisabled) return
                  onWatermark()
                }}
                className={watermarkDisabled ? "cursor-not-allowed opacity-60" : undefined}
              >
                <Droplet />
                Add watermark
                {watermarkDisabled && watermarkHint && (
                  <span className="sr-only">. {watermarkHint}</span>
                )}
              </Button>
            )}
            <Button size="sm" variant="outline" onClick={onDownload}>
              <Download />
              Download
            </Button>
          </div>
        </SheetHeader>

        <div ref={content} className="flex min-h-0 flex-1 flex-col bg-muted/30">
          {renderer === null || tooBigForText ? (
            <NoPreview
              archive={extensionOf(row.fileName) === "zip"}
              reason={
                tooBigForText
                  ? "This file is too large to show here."
                  : noPreviewReason(row.fileName, kind.label)
              }
              onDownload={onDownload}
            />
          ) : renderer === "slides" ? (
            <SlideViewer
              documentId={row.documentId}
              versionId={version?.versionId}
              title={row.title}
              zoom={zoom}
            />
          ) : file.isPending ? (
            <p className="m-auto inline-flex items-center gap-2 text-[13px] text-muted-foreground">
              <Loader2 size={16} className="animate-spin" /> Opening…
            </p>
          ) : file.isError ? (
            <LoadFailed error={file.error} fallback="Could not open the document." />
          ) : (
            <Viewer
              renderer={renderer}
              url={file.data.url}
              blob={file.data.blob}
              extension={extensionOf(row.fileName)}
              text={file.data.text}
              title={row.title}
              zoom={zoom}
              onDownload={onDownload}
            />
          )}
        </div>
      </SheetContent>
    </Sheet>
  )
}

/**
 * The panel's left edge: drag it sideways to make the panel wider or
 * narrower. Only sideways - the panel always fills the window's height.
 */
function ResizeHandle({
  width,
  onResize,
  onDragging,
}: {
  width: number
  onResize: (width: number) => void
  onDragging: (dragging: boolean) => void
}) {
  function start(event: ReactPointerEvent<HTMLDivElement>) {
    event.preventDefault()
    const handle = event.currentTarget
    handle.setPointerCapture(event.pointerId)
    onDragging(true)
    const move = (e: PointerEvent) => onResize(window.innerWidth - e.clientX)
    const stop = () => {
      handle.removeEventListener("pointermove", move)
      handle.removeEventListener("pointerup", stop)
      handle.removeEventListener("pointercancel", stop)
      onDragging(false)
    }
    handle.addEventListener("pointermove", move)
    handle.addEventListener("pointerup", stop)
    handle.addEventListener("pointercancel", stop)
  }

  return (
    <div
      role="separator"
      aria-orientation="vertical"
      aria-label="Resize the preview"
      aria-valuenow={width}
      aria-valuemin={MIN_WIDTH}
      tabIndex={0}
      title="Drag to resize"
      onPointerDown={start}
      onDoubleClick={() => onResize(DEFAULT_WIDTH)}
      onKeyDown={(event) => {
        if (event.key === "ArrowLeft") onResize(width + 40)
        else if (event.key === "ArrowRight") onResize(width - 40)
        else return
        event.preventDefault()
      }}
      className="group absolute inset-y-0 -left-1.5 z-[60] hidden w-3 cursor-col-resize touch-none items-center justify-center outline-none sm:flex"
    >
      <span className="h-full w-px bg-border transition-colors group-hover:bg-primary group-focus-visible:bg-primary group-active:bg-primary" />
      <span className="absolute h-10 w-1.5 rounded-full bg-border transition-colors group-hover:bg-primary group-focus-visible:bg-primary group-active:bg-primary" />
    </div>
  )
}

/**
 * A Word (.docx) file, laid out page by page in the browser (docx-preview,
 * loaded only when a Word file is opened). Its styles are scoped to it.
 */
function DocxViewer({
  blob,
  zoom: userZoom,
  onBroken,
}: {
  blob: Blob
  zoom: number
  onBroken: () => void
}) {
  const frame = useRef<HTMLDivElement>(null)
  const body = useRef<HTMLDivElement>(null)
  const styles = useRef<HTMLDivElement>(null)
  const [ready, setReady] = useState(false)
  // The laid-out page's own width; the page is scaled down to fit a narrower panel.
  const [pageWidth, setPageWidth] = useState(0)
  const [frameWidth, setFrameWidth] = useState(0)

  useEffect(() => {
    let cancelled = false
    void (async () => {
      try {
        const { renderAsync } = await import("docx-preview")
        if (cancelled || !body.current || !styles.current) return
        await renderAsync(blob, body.current, styles.current, {
          className: "docx",
          inWrapper: true,
          ignoreLastRenderedPageBreak: true,
          experimental: false,
          useBase64URL: true,
        })
        if (!cancelled) {
          setPageWidth(body.current.scrollWidth)
          setReady(true)
        }
      } catch {
        if (!cancelled) onBroken()
      }
    })()
    return () => {
      cancelled = true
    }
  }, [blob, onBroken])

  useEffect(() => {
    const element = frame.current
    if (!element) return
    const observer = new ResizeObserver(([entry]) =>
      setFrameWidth(entry.contentRect.width)
    )
    observer.observe(element)
    return () => observer.disconnect()
  }, [])

  // Fitted to the panel (never enlarged), then the reader's own zoom on top.
  const fit =
    pageWidth > 0 && frameWidth > 0 ? Math.min(1, frameWidth / pageWidth) : 1
  const zoom = fit * userZoom

  return (
    <div ref={frame} className="relative flex-1 overflow-auto bg-muted/40">
      {!ready && (
        <p className="absolute inset-0 m-auto flex items-center justify-center gap-2 text-[13px] text-muted-foreground">
          <Loader2 size={16} className="animate-spin" /> Laying out the
          document…
        </p>
      )}
      <div ref={styles} />
      <div ref={body} className="docx-preview-body" style={{ zoom }} />
    </div>
  )
}

function Viewer({
  renderer,
  url,
  blob,
  extension,
  text,
  title,
  zoom,
  onDownload,
}: {
  renderer: Renderer
  url: string
  blob: Blob
  extension: string
  text: string | null
  title: string
  zoom: number
  onDownload: () => void
}) {
  const [broken, setBroken] = useState(false)
  // Stable, so the Word viewer doesn't lay the file out again on every render.
  const markBroken = useCallback(() => setBroken(true), [])
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
        <ZoomableImage src={url} alt={title} zoom={zoom} onError={markBroken} />
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
    case "docx":
      return <DocxViewer blob={blob} zoom={zoom} onBroken={markBroken} />
    case "converted":
      return (
        <iframe
          src={url}
          title={title}
          className="h-full w-full flex-1 border-0 bg-white"
        />
      )
    case "sheet":
      return (
        <SheetViewer
          blob={blob}
          extension={extension}
          zoom={zoom}
          onBroken={markBroken}
        />
      )
    case "email":
      return (
        <EmailViewer
          blob={blob}
          extension={extension}
          zoom={zoom}
          onBroken={markBroken}
        />
      )
    case "heic":
      return (
        <HeicViewer
          blob={blob}
          title={title}
          zoom={zoom}
          onBroken={markBroken}
        />
      )
    case "tiff":
      return (
        <TiffViewer
          blob={blob}
          title={title}
          zoom={zoom}
          onBroken={markBroken}
        />
      )
    case "text":
      return (
        <pre
          className="flex-1 overflow-auto bg-card p-5 font-mono leading-[1.6] whitespace-pre-wrap"
          style={{ fontSize: 12.5 * zoom }}
        >
          {text}
        </pre>
      )
  }
}

function NoPreview({
  reason,
  archive = false,
  onDownload,
}: {
  reason: string
  /** A ZIP: shown as a downloadable archive rather than "no preview". */
  archive?: boolean
  onDownload: () => void
}) {
  const Icon = archive ? FileArchive : FileQuestion
  return (
    <div className="m-auto flex max-w-[420px] flex-col items-center gap-3 p-6 text-center">
      <span
        className={cn(
          "flex size-12 items-center justify-center rounded-full",
          archive
            ? "bg-[#986f0b]/10 text-[#986f0b]"
            : "bg-muted text-muted-foreground"
        )}
      >
        <Icon size={22} />
      </span>
      <p className="text-[14px] font-semibold">
        {archive ? "ZIP archive" : "No preview available"}
      </p>
      <p className="text-[13px] text-muted-foreground">
        {reason}{" "}
        {archive
          ? "Download it to see the files inside."
          : "Download it to open it on your computer."}
      </p>
      <Button size="sm" onClick={onDownload}>
        <Download />
        {archive ? "Download ZIP" : "Download"}
      </Button>
    </div>
  )
}
