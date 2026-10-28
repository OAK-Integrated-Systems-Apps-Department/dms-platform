"use client"

import { useCallback, useRef, useState } from "react"

import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { getTooltip } from "@/lib/tooltips"
import { cn } from "@/lib/utils"

/** A box to draw over the page, in normalized (0..1) page coordinates. */
export interface Highlight {
  key: string
  bbox: [number, number, number, number]
  /** Draws it as the selected one -- brighter, and scrolled to. */
  active?: boolean
  label?: string
}

interface ReviewPageViewerProps {
  imageUrl: string | null
  isLoading: boolean
  error: Error | null
  highlights: Highlight[]
  onHighlightClick?: (key: string) => void
  /** Set while the user is choosing a region for a specific field. */
  selecting?: boolean
  onRegionSelected?: (bbox: [number, number, number, number]) => void
  onCancelSelection?: () => void
}

const MIN_DRAG_FRACTION = 0.004

/**
 * The page image with the extracted-field boxes drawn over it, and a
 * rubber-band selection on top of that.
 *
 * Everything here is in NORMALIZED coordinates (0..1 of page width and
 * height) and rendered with percentage offsets. That is the whole trick:
 * the stored bboxes are normalized, so a highlight placed at `left:
 * x0*100%` lands on the text whatever the preview rendered at and
 * whatever the browser has scaled the image to -- no pixel arithmetic,
 * nothing to re-measure when the window resizes, and a selection dragged
 * at one zoom means the same region at another.
 */
export function ReviewPageViewer({
  imageUrl,
  isLoading,
  error,
  highlights,
  onHighlightClick,
  selecting = false,
  onRegionSelected,
  onCancelSelection,
}: ReviewPageViewerProps) {
  const surfaceRef = useRef<HTMLDivElement>(null)
  const [dragStart, setDragStart] = useState<{ x: number; y: number } | null>(null)
  const [dragNow, setDragNow] = useState<{ x: number; y: number } | null>(null)

  const pointFrom = useCallback((event: React.PointerEvent) => {
    const rect = surfaceRef.current?.getBoundingClientRect()
    if (!rect || rect.width === 0 || rect.height === 0) return null
    return {
      // Clamped: a drag that leaves the image still has to produce a box
      // inside the page, or the backend rejects the whole selection for
      // the sake of a few pixels of overshoot.
      x: Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width)),
      y: Math.min(1, Math.max(0, (event.clientY - rect.top) / rect.height)),
    }
  }, [])

  function handlePointerDown(event: React.PointerEvent) {
    if (!selecting) return
    const point = pointFrom(event)
    if (!point) return
    event.currentTarget.setPointerCapture(event.pointerId)
    setDragStart(point)
    setDragNow(point)
  }

  function handlePointerMove(event: React.PointerEvent) {
    if (!selecting || !dragStart) return
    setDragNow(pointFrom(event))
  }

  function handlePointerUp() {
    if (!selecting || !dragStart || !dragNow) {
      setDragStart(null)
      setDragNow(null)
      return
    }
    const x0 = Math.min(dragStart.x, dragNow.x)
    const y0 = Math.min(dragStart.y, dragNow.y)
    const x1 = Math.max(dragStart.x, dragNow.x)
    const y1 = Math.max(dragStart.y, dragNow.y)
    setDragStart(null)
    setDragNow(null)

    // A click, not a drag. Treated as "never mind" rather than sent as a
    // zero-area box the backend would reject with an error the user did
    // not earn.
    if (x1 - x0 < MIN_DRAG_FRACTION || y1 - y0 < MIN_DRAG_FRACTION) {
      onCancelSelection?.()
      return
    }
    onRegionSelected?.([x0, y0, x1, y1])
  }

  const selection =
    dragStart && dragNow
      ? {
          left: Math.min(dragStart.x, dragNow.x),
          top: Math.min(dragStart.y, dragNow.y),
          width: Math.abs(dragNow.x - dragStart.x),
          height: Math.abs(dragNow.y - dragStart.y),
        }
      : null

  if (error) {
    return (
      <div className="rounded-lg border border-[var(--rust)] bg-[var(--rust-tint)] p-[26px] text-[13.5px] text-[var(--rust)]">
        {error.message}
      </div>
    )
  }

  return (
    <div className="relative overflow-hidden rounded-lg border border-border bg-muted">
      {isLoading && (
        <div className="flex h-[520px] items-center justify-center text-[13px] text-muted-foreground">
          Rendering the page…
        </div>
      )}
      {imageUrl && (
        <div
          ref={surfaceRef}
          data-testid="page-surface"
          className={cn("relative select-none", selecting && "cursor-crosshair")}
          onPointerDown={handlePointerDown}
          onPointerMove={handlePointerMove}
          onPointerUp={handlePointerUp}
        >
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            src={imageUrl}
            alt="Document page"
            className="block w-full"
            draggable={false}
          />

          {highlights.map((highlight) => {
            const [x0, y0, x1, y1] = highlight.bbox
            return (
              // The only control on this screen with no words on it. A
              // rectangle drawn over the page shows where a value was read
              // but says nothing about being clickable, and the native
              // `title` it used to carry named the field without explaining
              // either half of that.
              //
              // `aria-label` stays alongside the tooltip rather than being
              // replaced by it: the button has no text content, so without
              // one it is an unnamed control -- a tooltip is a description,
              // not a name.
              <Tooltip key={highlight.key}>
                <TooltipTrigger
                  render={(props) => (
                    <button
                      {...props}
                      type="button"
                      aria-label={highlight.label ?? "Extracted field"}
                      data-testid={`highlight-${highlight.key}`}
                      onClick={() => onHighlightClick?.(highlight.key)}
                      // pointer-events off while selecting, so a rubber-band
                      // drag that starts over an existing highlight selects a
                      // region instead of clicking the field underneath it.
                      // It suppresses the tooltip there too, which is what we
                      // want -- nothing should pop up mid-drag.
                      className={cn(
                        "absolute rounded-[3px] border-2 transition-colors",
                        selecting && "pointer-events-none",
                        highlight.active
                          ? "border-[var(--gold-foreground)] bg-[var(--gold-tint)]/55"
                          : "border-primary/45 bg-primary/10 hover:bg-primary/20"
                      )}
                      style={{
                        left: `${x0 * 100}%`,
                        top: `${y0 * 100}%`,
                        width: `${(x1 - x0) * 100}%`,
                        height: `${(y1 - y0) * 100}%`,
                      }}
                    />
                  )}
                />
                <TooltipContent>
                  {highlight.label
                    ? `${highlight.label} — ${getTooltip("documents.review.highlight")}`
                    : getTooltip("documents.review.highlight")}
                </TooltipContent>
              </Tooltip>
            )
          })}

          {selection && (
            <div
              data-testid="rubber-band"
              className="pointer-events-none absolute border-2 border-dashed border-[var(--gold-foreground)] bg-[var(--gold-tint)]/40"
              style={{
                left: `${selection.left * 100}%`,
                top: `${selection.top * 100}%`,
                width: `${selection.width * 100}%`,
                height: `${selection.height * 100}%`,
              }}
            />
          )}
        </div>
      )}

      {selecting && (
        <div className="absolute inset-x-0 top-0 flex items-center justify-between gap-3 bg-[var(--gold-foreground)] px-3 py-2 text-[12.5px] font-semibold text-white">
          <span>Drag a box around the value on the page.</span>
          <button type="button" className="underline" onClick={onCancelSelection}>
            Cancel
          </button>
        </div>
      )}
    </div>
  )
}
