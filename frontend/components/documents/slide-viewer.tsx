"use client"

import { LoadFailed } from "@/components/ui/load-failed"
import {
  useEffect,
  useRef,
  useState,
  type KeyboardEvent as ReactKeyboardEvent,
} from "react"
import {
  useQuery,
  useQueryClient,
  type QueryClient,
} from "@tanstack/react-query"
import { ChevronLeft, ChevronRight, Loader2 } from "lucide-react"

import { Button } from "@/components/ui/button"
import { openSlides, slideImage } from "@/lib/documents/api"
import { cn } from "@/lib/utils"

/** Widths slides are drawn at - the smallest that's sharp on this screen is asked for. */
const WIDTHS = [480, 640, 960, 1280, 1600, 1920, 2400]
const THUMB_WIDTH = 240
/** The thumbnail strip shows when the viewer is at least this wide. */
const STRIP_FROM = 720
/** Room kept around the slide inside the panel. */
const GUTTER = 48

function drawWidth(pixels: number): number {
  return WIDTHS.find((width) => width >= pixels) ?? WIDTHS[WIDTHS.length - 1]
}

function slideQuery(
  documentId: string,
  versionId: string | undefined,
  slide: number,
  width: number
) {
  return {
    queryKey: ["slide", documentId, versionId ?? "current", slide, width],
    queryFn: async () =>
      URL.createObjectURL(
        await slideImage(documentId, versionId, slide, width)
      ),
    staleTime: Infinity,
    // Kept while the viewer is open (stepping back is instant); freed when it closes.
    gcTime: Infinity,
    retry: false,
  }
}

/** Frees every slide image of this document - their object URLs and their cache entries. */
function forgetSlides(queryClient: QueryClient, documentId: string) {
  for (const query of queryClient
    .getQueryCache()
    .findAll({ queryKey: ["slide", documentId] })) {
    const url = query.state.data
    if (typeof url === "string") URL.revokeObjectURL(url)
  }
  queryClient.removeQueries({ queryKey: ["slide", documentId] })
}

/** An element's size, kept up to date - for elements that appear later (a callback ref). */
function useBox<T extends HTMLElement>() {
  const [element, setElement] = useState<T | null>(null)
  const [box, setBox] = useState({ width: 0, height: 0 })
  useEffect(() => {
    if (!element) return
    const observer = new ResizeObserver(([entry]) =>
      setBox({
        width: entry.contentRect.width,
        height: entry.contentRect.height,
      })
    )
    observer.observe(element)
    return () => observer.disconnect()
  }, [element])
  return [setElement, box, element] as const
}

/**
 * A presentation (.ppt, .pptx, .odp) shown slide by slide, like a slide
 * show: one slide fitted to the panel, a strip of thumbnails beside it, and
 * previous/next (buttons, the arrow keys, Page Up/Down, Home/End). The server
 * converts the deck once and draws each slide as an image of the size this
 * screen needs (DocumentRenditionService); opening it is audited as a view.
 */
export function SlideViewer({
  documentId,
  versionId,
  title,
  zoom,
}: {
  documentId: string
  /** An older version from the history; absent = the current version. */
  versionId?: string
  title: string
  zoom: number
}) {
  const queryClient = useQueryClient()
  const deck = useQuery({
    queryKey: ["slide-deck", documentId, versionId ?? "current"],
    queryFn: () => openSlides(documentId, versionId),
    gcTime: 0,
    staleTime: Infinity,
    retry: false,
  })
  const [current, setCurrent] = useState(1)
  const [frame, box] = useBox<HTMLDivElement>()
  const [viewer, outer, viewerElement] = useBox<HTMLDivElement>()

  const count = deck.data?.slides ?? 0
  const aspect =
    deck.data && deck.data.height > 0
      ? deck.data.width / deck.data.height
      : 16 / 9
  const fitWidth = Math.max(
    0,
    Math.min(box.width - GUTTER, (box.height - GUTTER) * aspect)
  )
  const shownWidth = Math.round(fitWidth * zoom)
  const shownHeight = Math.round(shownWidth / aspect)
  const ratio = typeof window === "undefined" ? 1 : window.devicePixelRatio || 1
  const width = drawWidth(shownWidth * ratio)
  const showStrip = outer.width >= STRIP_FROM && count > 1

  const image = useQuery({
    ...slideQuery(documentId, versionId, current, width),
    enabled: count > 0 && shownWidth > 0,
  })

  // The slides either side are fetched ahead, so stepping through is instant.
  useEffect(() => {
    if (count === 0 || shownWidth === 0) return
    for (const next of [current + 1, current - 1]) {
      if (next >= 1 && next <= count)
        void queryClient.prefetchQuery(
          slideQuery(documentId, versionId, next, width)
        )
    }
  }, [queryClient, documentId, versionId, current, count, width, shownWidth])

  useEffect(() => {
    return () => forgetSlides(queryClient, documentId)
  }, [queryClient, documentId])

  // Keys work straight away, without clicking the slide first.
  useEffect(() => {
    viewerElement?.focus({ preventScroll: true })
  }, [viewerElement])

  function go(slide: number) {
    setCurrent(Math.min(Math.max(1, slide), Math.max(1, count)))
  }

  function onKeyDown(event: ReactKeyboardEvent<HTMLDivElement>) {
    const moves: Record<string, number> = {
      ArrowRight: current + 1,
      ArrowDown: current + 1,
      PageDown: current + 1,
      " ": current + 1,
      ArrowLeft: current - 1,
      ArrowUp: current - 1,
      PageUp: current - 1,
      Home: 1,
      End: count,
    }
    if (!(event.key in moves)) return
    event.preventDefault()
    go(moves[event.key])
  }

  if (deck.isPending) {
    return (
      <p className="m-auto inline-flex items-center gap-2 text-[13px] text-muted-foreground">
        <Loader2 size={16} className="animate-spin" /> Preparing the slides…
      </p>
    )
  }
  if (deck.isError) {
    return (
      <LoadFailed error={deck.error} fallback="Could not prepare the slides." />
    )
  }
  if (count === 0) {
    return (
      <p className="m-auto text-[13px] text-muted-foreground">
        This presentation has no slides.
      </p>
    )
  }

  return (
    <div
      ref={viewer}
      tabIndex={0}
      onKeyDown={onKeyDown}
      role="region"
      aria-roledescription="slide viewer"
      aria-label={`${title} - slide ${current} of ${count}`}
      className="flex min-h-0 flex-1 outline-none"
    >
      {showStrip && (
        <nav
          aria-label="Slides"
          className="flex w-[148px] shrink-0 flex-col gap-3 overflow-y-auto border-r border-border bg-background/60 p-3"
        >
          {Array.from({ length: count }, (_, i) => i + 1).map((slide) => (
            <Thumbnail
              key={slide}
              documentId={documentId}
              versionId={versionId}
              slide={slide}
              aspect={aspect}
              selected={slide === current}
              onSelect={() => go(slide)}
            />
          ))}
        </nav>
      )}

      <div className="flex min-w-0 flex-1 flex-col">
        <div ref={frame} className="relative min-h-0 flex-1 overflow-auto">
          <div className="flex min-h-full min-w-full items-center justify-center p-6">
            <div
              className="relative shrink-0 overflow-hidden rounded-sm bg-white shadow-[0_1px_3px_rgba(0,0,0,0.12),0_8px_24px_rgba(0,0,0,0.08)]"
              style={{ width: shownWidth, height: shownHeight }}
            >
              {image.data ? (
                // eslint-disable-next-line @next/next/no-img-element -- a private, already-fetched blob
                <img
                  src={image.data}
                  alt={`Slide ${current} of ${count}`}
                  draggable={false}
                  className="block size-full select-none"
                />
              ) : image.isError ? (
                <div className="flex size-full flex-col items-center justify-center gap-2 p-4 text-center text-[13px] text-[var(--rust)]">
                  <LoadFailed error={image.error} fallback="Could not show this slide." quiet />
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => void image.refetch()}
                  >
                    Try again
                  </Button>
                </div>
              ) : (
                <div className="flex size-full items-center justify-center text-muted-foreground">
                  <Loader2 size={20} className="animate-spin" />
                </div>
              )}
            </div>
          </div>
        </div>

        <div className="flex items-center justify-center gap-2 border-t border-border bg-background px-3 py-2">
          <Button
            size="icon-sm"
            variant="ghost"
            aria-label="Previous slide"
            title="Previous slide (←)"
            disabled={current <= 1}
            onClick={() => go(current - 1)}
          >
            <ChevronLeft />
          </Button>
          <span
            aria-live="polite"
            className="min-w-28 text-center text-[13px] text-muted-foreground tabular-nums"
          >
            Slide {current} of {count}
          </span>
          <Button
            size="icon-sm"
            variant="ghost"
            aria-label="Next slide"
            title="Next slide (→)"
            disabled={current >= count}
            onClick={() => go(current + 1)}
          >
            <ChevronRight />
          </Button>
        </div>
      </div>
    </div>
  )
}

/** One slide in the strip - drawn small, and only once it scrolls into view. */
function Thumbnail({
  documentId,
  versionId,
  slide,
  aspect,
  selected,
  onSelect,
}: {
  documentId: string
  versionId?: string
  slide: number
  aspect: number
  selected: boolean
  onSelect: () => void
}) {
  const button = useRef<HTMLButtonElement>(null)
  const [seen, setSeen] = useState(false)
  const image = useQuery({
    ...slideQuery(documentId, versionId, slide, THUMB_WIDTH),
    enabled: seen,
  })

  useEffect(() => {
    const element = button.current
    if (!element || seen) return
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) setSeen(true)
      },
      { rootMargin: "200px 0px" }
    )
    observer.observe(element)
    return () => observer.disconnect()
  }, [seen])

  useEffect(() => {
    if (selected) button.current?.scrollIntoView({ block: "nearest" })
  }, [selected])

  return (
    <button
      ref={button}
      type="button"
      tabIndex={-1}
      onClick={onSelect}
      aria-label={`Slide ${slide}`}
      aria-current={selected ? "true" : undefined}
      className="group flex items-start gap-1.5 text-left"
    >
      <span
        className={cn(
          "w-4 shrink-0 pt-0.5 text-right text-[11px] tabular-nums",
          selected ? "font-semibold text-primary" : "text-muted-foreground"
        )}
      >
        {slide}
      </span>
      <span
        className={cn(
          "block w-full overflow-hidden rounded-[3px] bg-white ring-1 ring-border transition-shadow",
          selected ? "ring-2 ring-primary" : "group-hover:ring-foreground/30"
        )}
        style={{ aspectRatio: aspect }}
      >
        {image.data && (
          // eslint-disable-next-line @next/next/no-img-element -- a private, already-fetched blob
          <img
            src={image.data}
            alt=""
            draggable={false}
            className="block size-full"
          />
        )}
      </span>
    </button>
  )
}
