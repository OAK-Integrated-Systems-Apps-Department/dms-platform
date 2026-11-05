"use client"

import { LoadFailed } from "@/components/ui/load-failed"
import { useCallback, useEffect, useState } from "react"
import {
  useQuery,
  useQueryClient,
  type QueryClient,
} from "@tanstack/react-query"
import { Loader2, Printer, ShieldCheck } from "lucide-react"
import { toast } from "sonner"

import { Button } from "@/components/ui/button"
import {
  openPages,
  pageImage,
  type PageDeckDto,
  type PreviewPolicyDto,
} from "@/lib/preview/api"
import { useNearViewport } from "@/lib/use-near-viewport"
import { cn } from "@/lib/utils"

/** Widths pages are drawn at - the smallest that's sharp on this screen is asked for. */
const WIDTHS = [480, 640, 800, 1000, 1200, 1500, 1800, 2400]
/** Room kept around a page inside the panel. */
const GUTTER = 32
/** A page is fetched this far before it scrolls into view. */
const LOOKAHEAD = "900px"
/** Pages printed from the preview are drawn this wide. */
const PRINT_WIDTH = 1400

function drawWidth(pixels: number): number {
  return WIDTHS.find((width) => width >= pixels) ?? WIDTHS[WIDTHS.length - 1]
}

function pageQuery(
  documentId: string,
  versionId: string | undefined,
  page: number,
  width: number
) {
  return {
    queryKey: ["page-image", documentId, versionId ?? "current", page, width],
    queryFn: async () =>
      URL.createObjectURL(await pageImage(documentId, versionId, page, width)),
    staleTime: Infinity,
    // Kept while the viewer is open (scrolling back is instant); freed when it closes.
    gcTime: Infinity,
    retry: false,
  }
}

/** Frees every page picture of this document - their object URLs and their cache entries. */
function forgetPages(queryClient: QueryClient, documentId: string) {
  for (const query of queryClient
    .getQueryCache()
    .findAll({ queryKey: ["page-image", documentId] })) {
    const url = query.state.data
    if (typeof url === "string") URL.revokeObjectURL(url)
  }
  queryClient.removeQueries({ queryKey: ["page-image", documentId] })
}

/** An element's width, kept up to date - for an element that appears later (a callback ref). */
function useWidth<T extends HTMLElement>() {
  const [element, setElement] = useState<T | null>(null)
  const [width, setWidth] = useState(0)
  useEffect(() => {
    if (!element) return
    const observer = new ResizeObserver(([entry]) =>
      // Whole steps of 4px, so a sub-pixel change can never set off another round.
        setWidth(Math.floor(entry.contentRect.width / 4) * 4)
    )
    observer.observe(element)
    return () => observer.disconnect()
  }, [element])
  return [setElement, width] as const
}

/**
 * Prints every page of the document as drawn by the server (the viewer's name burned in) through a hidden frame.
 * The browser's own print of this page is blanked while printing is restricted - see `body.dms-print-restricted`
 * in globals.css - so this is the only way a restricted preview reaches paper.
 */
async function printAllPages(
  documentId: string,
  versionId: string | undefined,
  count: number
) {
  const urls: string[] = []
  const frame = document.createElement("iframe")
  const cleanup = () => {
    urls.forEach((url) => URL.revokeObjectURL(url))
    frame.remove()
  }
  try {
    for (let page = 1; page <= count; page++) {
      urls.push(
        URL.createObjectURL(
          await pageImage(documentId, versionId, page, PRINT_WIDTH)
        )
      )
    }
    frame.setAttribute("aria-hidden", "true")
    frame.style.cssText =
      "position:fixed;right:0;bottom:0;width:0;height:0;border:0;visibility:hidden"
    document.body.appendChild(frame)
    const doc = frame.contentDocument
    if (!doc) throw new Error("no print frame")
    doc.open()
    doc.write(
      "<!doctype html><title>Print</title><style>@page{margin:0}" +
        "body{margin:0}img{display:block;width:100%;page-break-after:always}" +
        "</style>" +
        urls.map((url) => `<img src="${url}">`).join("")
    )
    doc.close()
    await Promise.all(
      Array.from(doc.images).map(
        (image) =>
          new Promise<void>((resolve) => {
            if (image.complete) resolve()
            else {
              image.onload = () => resolve()
              image.onerror = () => resolve()
            }
          })
      )
    )
    frame.contentWindow?.addEventListener("afterprint", cleanup)
    frame.contentWindow?.focus()
    frame.contentWindow?.print()
    // A browser that never reports "afterprint" must not leak the pictures.
    window.setTimeout(cleanup, 120_000)
  } catch {
    cleanup()
    toast.error("Could not prepare the pages for printing.")
  }
}

/**
 * A document shown as pictures of its pages, drawn on the server (DocumentRenditionService.pages): used whenever
 * the organisation restricts what a preview may do (print / download / copy). The file itself never reaches the
 * browser and there is no text to select, so a restriction can't be undone from the page; when the policy asks for
 * a watermark it is burned into the pictures, not laid over them. Pages are drawn as they scroll into view.
 */
export function PageViewer({
  documentId,
  versionId,
  title,
  zoom,
  policy,
}: {
  documentId: string
  /** An older version from the history; absent = the current version. */
  versionId?: string
  title: string
  zoom: number
  policy: PreviewPolicyDto
}) {
  const queryClient = useQueryClient()
  const deck = useQuery({
    queryKey: ["page-deck", documentId, versionId ?? "current"],
    queryFn: () => openPages(documentId, versionId),
    gcTime: 0,
    staleTime: Infinity,
    retry: false,
  })
  const [frame, frameWidth] = useWidth<HTMLDivElement>()
  const [printing, setPrinting] = useState(false)

  const count = deck.data?.pages ?? 0
  const aspect =
    deck.data && deck.data.height > 0 ? deck.data.width / deck.data.height : 0.75
  const shownWidth = Math.max(0, Math.round((frameWidth - GUTTER) * zoom))
  const ratio = typeof window === "undefined" ? 1 : window.devicePixelRatio || 1
  const width = drawWidth(shownWidth * ratio)

  useEffect(() => {
    return () => forgetPages(queryClient, documentId)
  }, [queryClient, documentId])

  // The browser's own print of the whole page is blanked while printing is restricted.
  useEffect(() => {
    if (policy.print === "allow") return
    document.body.classList.add("dms-print-restricted")
    return () => document.body.classList.remove("dms-print-restricted")
  }, [policy.print])

  const print = useCallback(async () => {
    if (printing || count === 0) return
    setPrinting(true)
    try {
      await printAllPages(documentId, versionId, count)
    } finally {
      setPrinting(false)
    }
  }, [printing, count, documentId, versionId])

  // Ctrl/Cmd+P follows the policy: blocked, or the watermarked print instead of the page's own.
  useEffect(() => {
    if (policy.print === "allow") return
    function onKeyDown(event: KeyboardEvent) {
      if (!(event.ctrlKey || event.metaKey) || event.key.toLowerCase() !== "p")
        return
      event.preventDefault()
      if (policy.print === "block")
        toast.info("Your organisation doesn't allow printing from a preview.")
      else void print()
    }
    window.addEventListener("keydown", onKeyDown)
    return () => window.removeEventListener("keydown", onKeyDown)
  }, [policy.print, print])

  if (deck.isPending) {
    return (
      <p className="m-auto inline-flex items-center gap-2 text-[13px] text-muted-foreground">
        <Loader2 size={16} className="animate-spin" /> Preparing the preview…
      </p>
    )
  }
  if (deck.isError) {
    return (
      <LoadFailed
        error={deck.error}
        fallback="Could not prepare the preview."
      />
    )
  }

  const data = deck.data
  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <Notices
        deck={data}
        policy={policy}
        printing={printing}
        onPrint={() => void print()}
      />
      <div
        ref={frame}
        // Nothing to save or copy from the page: no context menu, no dragging a picture out.
        onContextMenu={(event) => event.preventDefault()}
        role="region"
        aria-label={`${title} - ${count} ${count === 1 ? "page" : "pages"}`}
        // The vertical scrollbar always takes its room: a page width derived from this panel's width must not
        // change when the scrollbar appears or goes (which would resize the pages, and the scrollbar, forever).
        className="relative min-h-0 flex-1 overflow-x-auto overflow-y-scroll bg-muted/40 select-none [scrollbar-gutter:stable]"
      >
        {count === 0 ? (
          <p className="m-auto p-6 text-[13px] text-muted-foreground">
            This file has no pages to show.
          </p>
        ) : (
          <ol className="mx-auto flex w-max min-w-full flex-col items-center gap-3 p-4">
            {Array.from({ length: count }, (_, index) => (
              <PageSlot
                key={index + 1}
                documentId={documentId}
                versionId={versionId}
                number={index + 1}
                total={count}
                width={width}
                shownWidth={shownWidth}
                aspect={aspect}
              />
            ))}
          </ol>
        )}
      </div>
    </div>
  )
}

/** The notes above the pages: what restricts this preview, and the partial-preview notice. */
function Notices({
  deck,
  policy,
  printing,
  onPrint,
}: {
  deck: PageDeckDto
  policy: PreviewPolicyDto
  printing: boolean
  onPrint: () => void
}) {
  const parts: string[] = []
  if (deck.watermarked) parts.push("Your name and the time are on every page.")
  if (policy.copy === "block" || policy.print !== "allow")
    parts.push("Shown as pictures - the text can't be selected.")
  const partial = deck.partial
    ? deck.totalPages
      ? `Showing the first ${deck.pages} of ${deck.totalPages} pages.`
      : `Showing the first ${deck.pages} pages only.`
    : null
  if (parts.length === 0 && !partial && policy.print !== "watermark")
    return null
  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1 border-b border-border bg-card px-4 py-2 text-[12.5px] text-muted-foreground">
      {parts.length > 0 && (
        <span className="inline-flex items-center gap-1.5">
          <ShieldCheck size={14} aria-hidden="true" className="text-primary" />
          {parts.join(" ")}
        </span>
      )}
      {partial && (
        <span className="font-medium text-foreground">
          {partial}
          {policy.download === "block"
            ? " The rest can't be downloaded from a preview."
            : " Download the file to see the rest."}
        </span>
      )}
      {policy.print === "watermark" && (
        <Button
          size="xs"
          variant="outline"
          className="ml-auto"
          disabled={printing}
          onClick={onPrint}
        >
          {printing ? <Loader2 className="animate-spin" /> : <Printer />}
          Print with watermark
        </Button>
      )}
    </div>
  )
}

/** One page: a placeholder of the right shape until it is near the screen, then its picture. */
function PageSlot({
  documentId,
  versionId,
  number,
  total,
  width,
  shownWidth,
  aspect,
}: {
  documentId: string
  versionId?: string
  number: number
  total: number
  width: number
  shownWidth: number
  aspect: number
}) {
  const [ref, near] = useNearViewport<HTMLLIElement>(LOOKAHEAD)
  const image = useQuery({
    ...pageQuery(documentId, versionId, number, width),
    enabled: near && shownWidth > 0,
  })
  return (
    <li
      ref={ref}
      aria-label={`Page ${number} of ${total}`}
      style={{ width: shownWidth, aspectRatio: String(aspect) }}
      className={cn(
        "relative shrink-0 overflow-hidden bg-white shadow-sm ring-1 ring-border"
      )}
    >
      {image.data ? (
        // eslint-disable-next-line @next/next/no-img-element -- a blob URL of a server-drawn page
        <img
          src={image.data}
          alt={`Page ${number} of ${total}`}
          draggable={false}
          className="block size-full object-contain select-none"
        />
      ) : image.isError ? (
        <p className="absolute inset-0 flex items-center justify-center p-3 text-center text-[12.5px] text-muted-foreground">
          This page couldn&apos;t be shown.
        </p>
      ) : (
        <span className="absolute inset-0 flex items-center justify-center text-muted-foreground">
          <Loader2 size={16} className="animate-spin" aria-hidden="true" />
        </span>
      )}
    </li>
  )
}
