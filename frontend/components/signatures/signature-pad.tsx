"use client"

import { useEffect, useRef, useState } from "react"
import { Eraser, Trash2 } from "lucide-react"

import { cn } from "@/lib/utils"

interface SignaturePadProps {
  /** Base64 PNG (no "data:" prefix) to redraw on mount/when it changes externally, or null for a blank pad. */
  value: string | null
  /** Called with a base64 PNG (no "data:" prefix) after each completed stroke, or null after everything is erased/cleared. */
  onChange: (imageBase64: string | null) => void
  disabled?: boolean
}

// Smaller and thinner than the first version - "too big and too thick" was the exact complaint; these
// are deliberately modest (a signature-sized box, not a whiteboard).
const WIDTH = 320
const HEIGHT = 100
const PEN_WIDTH = 1.5
const ERASER_WIDTH = 14
// Fixed, not theme-aware: a signature pad is conventionally a plain light surface to draw dark ink
// on, like paper - using the app's own `--secondary` (near-black in dark mode) made a dark ink color
// on it practically invisible.
const PAD_BACKGROUND = "#fbfaf6"

const INK_COLORS = [
  { label: "Black", value: "#1a1a1a" },
  { label: "Blue", value: "#1d4ed8" },
  { label: "Red", value: "#b91c1c" },
] as const

/**
 * A plain HTML5 canvas signature pad - mouse/touch/pen via the Pointer
 * Events API, no drawing library. A pen/eraser toggle (the eraser punches
 * actual transparent holes via `globalCompositeOperation: "destination-out"`,
 * not just a full-canvas Clear) and a 3-color palette. Emits a base64 PNG
 * (via `toDataURL`, stripped of its "data:image/png;base64," prefix - the
 * backend's `UserVisualSignatureService` strips it too, defensively, but
 * there is no reason to send it twice) after every stroke, so the parent
 * form always holds the latest drawing without needing its own "sign"
 * button for the pad. Erasing everything away reports `null`, same as
 * Clear - checked by scanning the canvas's own alpha channel, not just
 * "was Clear clicked", so erasing stroke-by-stroke down to nothing behaves
 * the same way.
 */
export function SignaturePad({ value, onChange, disabled }: SignaturePadProps) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null)
  const drawingRef = useRef(false)
  const lastPointRef = useRef<{ x: number; y: number } | null>(null)
  const [color, setColor] = useState<string>(INK_COLORS[0].value)
  const [tool, setTool] = useState<"pen" | "eraser">("pen")

  // Redraws `value` whenever it changes from OUTSIDE a stroke this pad just emitted (e.g. switching
  // back to a previously-saved drawn signature) - a ref flag distinguishes that from our own onChange.
  const lastEmittedRef = useRef<string | null>(null)
  useEffect(() => {
    const canvas = canvasRef.current
    const ctx = canvas?.getContext("2d")
    if (!canvas || !ctx) return
    if (!value) {
      ctx.clearRect(0, 0, WIDTH, HEIGHT)
      return
    }
    // This is the exact base64 our own `finish()` just emitted from live strokes already on the
    // canvas - redrawing it from the image would be a no-op at best, and clearing first (as this used
    // to, unconditionally) would briefly blank what the user just drew before putting it back - visible
    // as "lifting the pen erases it" on anything slower than instant.
    if (value === lastEmittedRef.current) return
    ctx.clearRect(0, 0, WIDTH, HEIGHT)
    const img = new Image()
    img.onload = () => ctx.drawImage(img, 0, 0, WIDTH, HEIGHT)
    img.src = `data:image/png;base64,${value}`
  }, [value])

  // The canvas's internal drawing resolution (WIDTH x HEIGHT) is fixed, but it's DISPLAYED at
  // `w-full` - almost always a different CSS size than that, since it stretches to whatever panel
  // it's in. Without rescaling, a stroke drawn away from the top-left corner lands at the wrong
  // internal position - this is what made drawing look "not visible": most strokes fell outside the
  // actual canvas, or were drawn at the wrong spot relative to the cursor.
  function point(event: React.PointerEvent<HTMLCanvasElement>) {
    const canvas = event.currentTarget
    const rect = canvas.getBoundingClientRect()
    const scaleX = canvas.width / rect.width
    const scaleY = canvas.height / rect.height
    return { x: (event.clientX - rect.left) * scaleX, y: (event.clientY - rect.top) * scaleY }
  }

  function start(event: React.PointerEvent<HTMLCanvasElement>) {
    if (disabled) return
    event.currentTarget.setPointerCapture(event.pointerId)
    drawingRef.current = true
    lastPointRef.current = point(event)
  }

  function draw(event: React.PointerEvent<HTMLCanvasElement>) {
    if (!drawingRef.current || disabled) return
    const canvas = canvasRef.current
    const ctx = canvas?.getContext("2d")
    const last = lastPointRef.current
    if (!canvas || !ctx || !last) return
    const next = point(event)
    ctx.globalCompositeOperation = tool === "eraser" ? "destination-out" : "source-over"
    ctx.strokeStyle = color
    ctx.lineWidth = tool === "eraser" ? ERASER_WIDTH : PEN_WIDTH
    ctx.lineCap = "round"
    ctx.beginPath()
    ctx.moveTo(last.x, last.y)
    ctx.lineTo(next.x, next.y)
    ctx.stroke()
    lastPointRef.current = next
  }

  function finish() {
    if (!drawingRef.current) return
    drawingRef.current = false
    lastPointRef.current = null
    const canvas = canvasRef.current
    const ctx = canvas?.getContext("2d")
    if (!canvas || !ctx) return
    if (!hasVisibleContent(ctx)) {
      lastEmittedRef.current = null
      onChange(null)
      return
    }
    const dataUrl = canvas.toDataURL("image/png")
    const base64 = dataUrl.slice(dataUrl.indexOf(",") + 1)
    lastEmittedRef.current = base64
    onChange(base64)
  }

  function clear() {
    const ctx = canvasRef.current?.getContext("2d")
    ctx?.clearRect(0, 0, WIDTH, HEIGHT)
    lastEmittedRef.current = null
    onChange(null)
  }

  return (
    <div>
      <canvas
        ref={canvasRef}
        width={WIDTH}
        height={HEIGHT}
        onPointerDown={start}
        onPointerMove={draw}
        onPointerUp={finish}
        onPointerLeave={finish}
        className="w-full max-w-[320px] touch-none rounded-btn border border-[var(--border-strong)]"
        style={{
          aspectRatio: `${WIDTH} / ${HEIGHT}`,
          cursor: disabled ? "not-allowed" : tool === "eraser" ? "cell" : "crosshair",
          backgroundColor: PAD_BACKGROUND,
        }}
        role="img"
        aria-label="Draw your signature"
      />

      <div className="mt-1.5 flex flex-wrap items-center gap-3">
        <div className="flex items-center gap-1.5">
          {INK_COLORS.map((c) => (
            <button
              key={c.value}
              type="button"
              title={c.label}
              aria-label={c.label}
              aria-pressed={tool === "pen" && color === c.value}
              disabled={disabled}
              onClick={() => {
                setColor(c.value)
                setTool("pen")
              }}
              className={cn(
                "size-5 rounded-full border-2 disabled:cursor-not-allowed disabled:opacity-60",
                tool === "pen" && color === c.value ? "border-primary" : "border-transparent"
              )}
              style={{ backgroundColor: c.value }}
            />
          ))}
        </div>

        <div className="h-4 w-px bg-border" />

        <button
          type="button"
          onClick={() => setTool((t) => (t === "eraser" ? "pen" : "eraser"))}
          disabled={disabled}
          aria-pressed={tool === "eraser"}
          className={cn(
            "flex items-center gap-1 border disabled:cursor-not-allowed disabled:opacity-60 h-[38px] rounded-btn px-4 text-[13px] font-semibold justify-center",
            tool === "eraser"
              ? "border-primary bg-primary/10 text-primary"
              : "border-[var(--border-strong)] text-muted-foreground hover:text-foreground"
          )}
        >
          <Eraser size={12} aria-hidden="true" />
          Eraser
        </button>

        <button
          type="button"
          onClick={clear}
          disabled={disabled}
          className="flex items-center gap-1 text-[12px] font-semibold text-muted-foreground hover:text-foreground disabled:cursor-not-allowed disabled:opacity-60"
        >
          <Trash2 size={12} aria-hidden="true" />
          Clear all
        </button>
      </div>
    </div>
  )
}

function hasVisibleContent(ctx: CanvasRenderingContext2D): boolean {
  const { data } = ctx.getImageData(0, 0, WIDTH, HEIGHT)
  for (let i = 3; i < data.length; i += 4) {
    if (data[i] !== 0) return true
  }
  return false
}
