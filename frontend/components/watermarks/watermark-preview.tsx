"use client"

import { useEffect, useRef, useState } from "react"
import { AlertTriangle, Loader2 } from "lucide-react"

import { ApiError } from "@/lib/api-error"
import { previewWatermark } from "@/lib/watermarks/api"

import type { WatermarkPreviewRequestDto } from "@/lib/watermarks/api"

/**
 * How long typing has to stop before a preview is rendered. Long enough
 * that a sentence typed at speed costs one render rather than thirty, short
 * enough that it still feels live.
 */
const DEBOUNCE_MS = 400

interface WatermarkPreviewProps {
  request: WatermarkPreviewRequestDto
  /** False while the form is invalid - nothing is sent and the box explains why. */
  enabled: boolean
  /**
   * Lets the dialog surface a blocking problem rather than only a grey box:
   * something the server cannot render now will fail on submit too, and the
   * user should know before they press the button, not after.
   */
  onUnrenderable?: (message: string | null) => void
}

/**
 * The live sample, straight from `POST /watermark-templates/preview`.
 *
 * Three things here are load-bearing rather than polish:
 *
 * <b>Cancellation.</b> Every keystroke supersedes the request before it. An
 * AbortController per attempt means the stale one is dropped at the network
 * layer; without it, a slow earlier render can land after a fast later one
 * and the box ends up showing text the user has already changed.
 *
 * <b>Object URL cleanup.</b> Each PNG becomes a blob URL, and each one is
 * revoked when it is replaced or the component unmounts. A dialog left open
 * while someone tries wordings would otherwise leak a few hundred KB per
 * keystroke for as long as the tab lives.
 *
 * <b>422 is not an error state here.</b> "This script has no font on the
 * server" and "this text is too long for the page" are answers about the
 * input, so they are shown as a warning the user can act on and reported
 * upward, not swallowed into a generic failure.
 */
export function WatermarkPreview({ request, enabled, onUnrenderable }: WatermarkPreviewProps) {
  const [url, setUrl] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  /**
   * True when the current problem was handed to `onUnrenderable`, which
   * means the dialog is already showing it beside the submit button it
   * disables. The same sentence twice in one narrow panel reads as a
   * stutter, so this box falls back to explaining the empty frame instead.
   */
  const [reportedUpward, setReportedUpward] = useState(false)
  // The last blob URL, so unmount can revoke it without a setState that
  // would run after the component is gone.
  const current = useRef<string | null>(null)

  // Derived rather than cleared in the effect: while the form is invalid
  // nothing has been sent, so a problem reported about an earlier, valid
  // request is stale and simply isn't shown. Clearing it with a setState in
  // the effect body would be a second render for the same information.
  const shownProblem = enabled ? problem : null

  // Serialised so the effect re-runs on a VALUE change rather than on every
  // render - `request` is rebuilt each time the form state changes, so a
  // reference dependency would re-request on keystrokes that changed
  // nothing relevant.
  const key = JSON.stringify(request)

  useEffect(() => {
    if (!enabled) return

    const controller = new AbortController()
    const timer = setTimeout(async () => {
      setLoading(true)
      try {
        const blob = await previewWatermark(JSON.parse(key) as WatermarkPreviewRequestDto, controller.signal)
        if (controller.signal.aborted) return
        const next = URL.createObjectURL(blob)
        if (current.current) URL.revokeObjectURL(current.current)
        current.current = next
        setUrl(next)
        setProblem(null)
        setReportedUpward(false)
        onUnrenderable?.(null)
      } catch (error) {
        if (controller.signal.aborted) return
        // An aborted fetch surfaces as a network-shaped ApiError(0) - not a
        // real failure, just a superseded request.
        if (error instanceof ApiError && error.status === 0) return

        const status = error instanceof ApiError ? error.status : 0
        const message =
          error instanceof ApiError && (status === 422 || status === 400 || status === 503)
            ? error.message
            : status === 429
              ? "Too many previews just now - it will catch up in a moment."
              : "Could not render a preview."
        const blocking = status === 422 || status === 503
        setProblem(message)
        setReportedUpward(blocking && onUnrenderable !== undefined)
        // 422 (this text can't be drawn) and 503 (this server can't draw at
        // all - no usable font) both mean the real request would fail the
        // same way, so they block submitting and carry the server's own
        // wording. 503 has to be caught HERE because nothing else reports
        // it: the templates list, which `useWatermarkAvailability` probes,
        // answers 200 even when the font probe has failed - only the
        // endpoints that actually draw call the availability guard.
        //
        // A rate limit is about the preview endpoint alone and must not
        // block submitting.
        onUnrenderable?.(blocking ? message : null)
      } finally {
        if (!controller.signal.aborted) setLoading(false)
      }
    }, DEBOUNCE_MS)

    return () => {
      clearTimeout(timer)
      controller.abort()
    }
    // onUnrenderable is intentionally omitted: callers pass an inline
    // function, so depending on it would re-request on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, enabled])

  useEffect(() => {
    return () => {
      if (current.current) URL.revokeObjectURL(current.current)
      current.current = null
    }
  }, [])

  return (
    <section aria-label="Watermark preview" className="flex flex-col gap-2">
      <div className="flex items-center justify-between">
        <span className="text-[11px] font-semibold tracking-[0.07em] text-muted-foreground uppercase">
          Preview
        </span>
        {loading && (
          <span className="flex items-center gap-1.5 text-[12px] text-muted-foreground">
            <Loader2 className="size-3 animate-spin" aria-hidden="true" />
            Rendering…
          </span>
        )}
      </div>

      <div
        className="relative flex min-h-[150px] items-center justify-center overflow-hidden rounded-lg border border-border bg-[repeating-conic-gradient(var(--muted)_0%_25%,transparent_0%_50%)] bg-[length:16px_16px] p-2"
        aria-live="polite"
      >
        {url ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img
            src={url}
            alt="A sample of how this watermark will look. Placeholders show example values, not your own details."
            className="max-h-[220px] w-auto max-w-full object-contain"
          />
        ) : (
          <p className="px-4 text-center text-[12.5px] text-muted-foreground">
            {!enabled
              ? "Choose a template or type some text to see a preview."
              : shownProblem
                ? "No sample to show."
                : "Rendering a sample…"}
          </p>
        )}
      </div>

      {shownProblem && !reportedUpward && (
        <p className="flex items-start gap-1.5 text-[12px] leading-[1.5] text-[var(--rust)]">
          <AlertTriangle size={13} className="mt-0.5 shrink-0" aria-hidden="true" />
          {shownProblem}
        </p>
      )}
      <p className="text-[11.5px] leading-[1.5] text-muted-foreground">
        Placeholders show example values here. The real copy uses your own details.
      </p>
    </section>
  )
}
