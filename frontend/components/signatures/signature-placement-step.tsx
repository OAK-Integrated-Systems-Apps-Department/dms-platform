"use client"

import { useRef, useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { ChevronLeft, ChevronRight, Eye, Loader2, Trash2 } from "lucide-react"
import { toast } from "sonner"

import { friendlyMessage } from "@/lib/api-error"
import { useCurrentUser } from "@/lib/auth/use-current-user"
import { useDocument } from "@/lib/documents/use-document"
import {
  fetchSignaturePlacementInfo,
  fetchSignaturePlacementPage,
  placeSignatureOnDocument,
  removeSignaturePlacement,
  type VisualSignatureKind,
} from "@/lib/signatures/api"
import { cn, formatSignatureStampDate } from "@/lib/utils"

import { Checkbox } from "@/components/ui/checkbox"
import { useDocumentPreview } from "@/components/workflow/use-document-preview"

interface SignaturePlacementStepProps {
  taskId: string
  visualSignatureKind: VisualSignatureKind
  visualSignatureText: string
  visualSignatureImageBase64: string | null
  onBack: () => void
  /** Called once placement has either succeeded, or the file type doesn't support it (nothing to place). */
  onDone: () => void
}

/**
 * Default drag-box size as a fraction of the page. Only an approximate preview of where the mark will
 * land - the backend (`DocumentStampService#aspectHeight`) always derives the actual stamped height from
 * the composed mark+Name/Designation/Date image's own aspect ratio, never from this box's height, so that
 * text is never stretched/squashed to fit a box sized for a bare signature line. Width and position (x/y)
 * are what the backend actually honors.
 */
const DEFAULT_WIDTH_RATIO = 0.28
const DEFAULT_HEIGHT_RATIO = 0.09
const PAGE_WIDTH = 1400

/**
 * "Place it on the document" - the step between picking a visual signature
 * and confirming with a password (see `SignAndApproveDialog`). Shows the
 * current page as an image and lets the signer drag their mark to where it
 * should go; "Place signature" burns it in via `placeSignatureOnDocument`,
 * creating a new document version that sign-and-approve (called right after)
 * signs. Skipped automatically (via `onDone`) for a file type
 * `DocumentStampService` can't stamp - the signature is still recorded as
 * cosmetic evidence (`SignAndApproveDialog` still sends it along), just not
 * drawn onto the page itself.
 */
export function SignaturePlacementStep({
  taskId,
  visualSignatureKind,
  visualSignatureText,
  visualSignatureImageBase64,
  onBack,
  onDone,
}: SignaturePlacementStepProps) {
  const queryClient = useQueryClient()
  const { data: info, isPending: infoPending } = useQuery({
    queryKey: ["signature-placement-info", taskId],
    queryFn: () => fetchSignaturePlacementInfo(taskId),
  })
  const { data: document } = useDocument(info?.documentId ?? "")
  const { data: currentUser, isError: currentUserError } = useCurrentUser()
  const preview = useDocumentPreview()
  const [page, setPage] = useState(0)
  const [box, setBox] = useState({ x: 0.36, y: 0.75, width: DEFAULT_WIDTH_RATIO, height: DEFAULT_HEIGHT_RATIO })
  const [includeSignerDetails, setIncludeSignerDetails] = useState(true)
  const imageRef = useRef<HTMLDivElement | null>(null)
  const dragRef = useRef<{ startX: number; startY: number; boxX: number; boxY: number } | null>(null)

  // staleTime/gcTime: Infinity - this component remounts on every step 2 <-> 1 visit (it's only
  // conditionally rendered, see SignAndApproveDialog), and re-fetching + re-rendering the page image
  // every single time would be wasteful when nothing about the document has changed in between.
  // Deliberately never revokes the object URL on unmount: since the query cache outlives this
  // component's mount (that's what gcTime: Infinity means), revoking on unmount would leave a dangling
  // reference to an already-revoked URL for the NEXT mount to serve straight from cache - which loaded
  // as a broken image. A dialog session leaks at most a couple of these until it closes; see
  // slide-viewer.tsx's forgetSlides for the pattern of revoking tied to the whole viewer closing,
  // which would be the right fix if this needed to live longer than one dialog.
  const pageQuery = useQuery({
    queryKey: ["signature-placement-page", taskId, page],
    queryFn: async () => URL.createObjectURL(await fetchSignaturePlacementPage(taskId, page, PAGE_WIDTH)),
    enabled: Boolean(info?.supported),
    staleTime: Infinity,
    gcTime: Infinity,
  })

  const place = useMutation({
    mutationFn: () =>
      placeSignatureOnDocument(taskId, {
        page,
        xRatio: box.x,
        yRatio: box.y,
        widthRatio: box.width,
        heightRatio: box.height,
        visualSignatureKind,
        visualSignatureText: visualSignatureKind === "typed" ? visualSignatureText : null,
        visualSignatureImageBase64: visualSignatureKind !== "typed" ? visualSignatureImageBase64 : null,
        includeSignerDetails,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["signatures"] })
      onDone()
    },
  })

  const remove = useMutation({
    mutationFn: () => removeSignaturePlacement(taskId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["signature-placement-info", taskId] })
      queryClient.invalidateQueries({ queryKey: ["signatures"] })
      toast.success("Signature removed - you can place it again.")
    },
  })

  function startDrag(event: React.PointerEvent<HTMLDivElement>) {
    event.currentTarget.setPointerCapture(event.pointerId)
    dragRef.current = { startX: event.clientX, startY: event.clientY, boxX: box.x, boxY: box.y }
  }

  function onDrag(event: React.PointerEvent<HTMLDivElement>) {
    const drag = dragRef.current
    const container = imageRef.current
    if (!drag || !container) return
    const rect = container.getBoundingClientRect()
    const dx = (event.clientX - drag.startX) / rect.width
    const dy = (event.clientY - drag.startY) / rect.height
    setBox((prev) => ({
      ...prev,
      x: clamp(drag.boxX + dx, 0, 1 - prev.width),
      y: clamp(drag.boxY + dy, 0, 1 - prev.height),
    }))
  }

  function endDrag() {
    dragRef.current = null
  }

  if (infoPending) {
    return <p className="py-6 text-center text-[13px] text-muted-foreground">Loading document…</p>
  }
  if (!info) {
    return <p className="py-6 text-center text-[13px] text-destructive">Could not load this document.</p>
  }

  if (!info.supported) {
    return (
      <div className="text-left">
        <p className="mb-5 rounded-btn border border-[var(--border-strong)] bg-[var(--secondary)] px-3 py-2.5 text-[12.5px] text-muted-foreground">
          This file type can&apos;t be marked up directly - your signature will still be recorded with this
          approval, just not drawn onto the page itself.
        </p>
        <div className="flex items-center justify-center gap-2.5">
          <button
            type="button"
            onClick={onBack}
            className="rounded-md border border-[var(--border-strong)] bg-card px-5 py-2.5 text-[13px] font-semibold text-foreground"
          >
            Back
          </button>
          <button
            type="button"
            onClick={onDone}
            className="rounded-md border border-primary bg-primary px-5 py-2.5 text-[13px] font-bold tracking-[0.02em] text-primary-foreground"
          >
            Continue
          </button>
        </div>
      </div>
    )
  }

  return (
    <div className="text-left">
      <div className="mb-3 flex items-center justify-between gap-3">
        <p className="text-[12.5px] text-muted-foreground">Drag your signature to where it should go on the page.</p>
        {document && (
          <button
            type="button"
            onClick={() => preview.open(document)}
            className="flex shrink-0 items-center gap-1.5 whitespace-nowrap rounded-md border border-[var(--border-strong)] px-2.5 py-1 text-[12px] font-semibold text-foreground hover:bg-muted"
          >
            <Eye size={13} />
            Read document
          </button>
        )}
      </div>

      <div className="mb-3 rounded-btn border border-[var(--border-strong)] px-3 py-1.5">
        <Checkbox
          id="sign-approve-include-details"
          label="Include my name, designation, department and date"
          hint="Turn off to stamp just the signature mark - your identity and signing time stay in the cryptographic signature either way."
          checked={includeSignerDetails}
          onCheckedChange={setIncludeSignerDetails}
        />
      </div>

      {info.pageCount > 1 && (
        <div className="mb-2 flex items-center justify-center gap-3 text-[12px] font-semibold text-muted-foreground">
          <button
            type="button"
            onClick={() => setPage((p) => Math.max(0, p - 1))}
            disabled={page === 0}
            className="disabled:cursor-not-allowed disabled:opacity-40"
          >
            <ChevronLeft size={14} />
          </button>
          Page {page + 1} of {info.pageCount}
          <button
            type="button"
            onClick={() => setPage((p) => Math.min(info.pageCount - 1, p + 1))}
            disabled={page === info.pageCount - 1}
            className="disabled:cursor-not-allowed disabled:opacity-40"
          >
            <ChevronRight size={14} />
          </button>
        </div>
      )}

      {/* Shrink-wrapped (inline-block) and centered, rather than stretched to the dialog's own width -
          the page is capped to a modest viewport-relative height so a tall/portrait page can't push the
          rest of the dialog off-screen (AlertDialogPopup itself is also scrollable as a second safety
          net). Letting the box shrink-wrap to the image's ACTUAL rendered size, whichever dimension ends
          up the limiting one, keeps the drag overlay's percentage-based positioning correct - it's
          computed from this container's own bounding rect, which only works if that rect really is the
          image's rendered box, not a wider one with letterboxing around it. */}
      <div className="flex justify-center">
        <div
          ref={imageRef}
          className="relative inline-block overflow-hidden rounded-btn border border-[var(--border-strong)] bg-[var(--secondary)]"
        >
          {pageQuery.isPending ? (
            <div className="flex h-64 w-[320px] items-center justify-center">
              <Loader2 size={20} className="animate-spin text-muted-foreground" />
            </div>
          ) : pageQuery.data ? (
            // eslint-disable-next-line @next/next/no-img-element -- a locally-generated object URL, not a remote image
            <img
              src={pageQuery.data}
              alt={`Page ${page + 1}`}
              className="block max-h-[78vh] w-auto max-w-full select-none"
              draggable={false}
            />
          ) : (
            <p className="p-6 text-center text-[12.5px] text-destructive">Could not load this page.</p>
          )}

          {!pageQuery.isPending && pageQuery.data && (
            <div
              onPointerDown={startDrag}
              onPointerMove={onDrag}
              onPointerUp={endDrag}
              className="absolute cursor-move touch-none rounded-sm border-2 border-dashed border-foreground/50 bg-foreground/5"
              style={{
                left: `${box.x * 100}%`,
                top: `${box.y * 100}%`,
                width: `${box.width * 100}%`,
                height: `${box.height * 100}%`,
              }}
            >
              {visualSignatureKind === "typed" ? (
                <span className="flex h-full w-full items-center justify-center overflow-hidden text-[20px] font-signature text-foreground">
                  {visualSignatureText}
                </span>
              ) : visualSignatureImageBase64 ? (
                // eslint-disable-next-line @next/next/no-img-element -- a small inline base64 PNG
                <img
                  src={`data:image/png;base64,${visualSignatureImageBase64}`}
                  alt="Your signature"
                  className="h-full w-full object-contain"
                  draggable={false}
                />
              ) : null}
            </div>
          )}

          {/* Approximate preview only - never sent anywhere. The real Name/Position/Department/Date
              block (SignatureStampComposer, backend) is composed into the mark itself at place() time;
              this just shows roughly what that will say, right under the drag box, so it doesn't look
              like only the bare mark gets stamped. Fixed black-on-white (never `text-foreground`,
              theme-dependent and near-white in dark mode) - this sits on the rendered PAGE, always a
              light raster image regardless of the app's own theme, exactly like the real stamp always
              draws this text in solid black (SignatureStampComposer). */}
          {!pageQuery.isPending && pageQuery.data && currentUser && includeSignerDetails && (
            <div
              className="pointer-events-none absolute w-max max-w-[55%] select-none rounded-sm bg-white/90 px-1 py-0.5 text-[10px] leading-tight text-black shadow-sm"
              style={{ left: `${box.x * 100}%`, top: `${(box.y + box.height) * 100}%`, marginTop: 2 }}
            >
              <p className="font-semibold">{currentUser.displayName}</p>
              {currentUser.position && <p>{currentUser.position}</p>}
              {currentUser.departmentName && <p>{currentUser.departmentName}</p>}
              <p>Signed: {formatSignatureStampDate(new Date())}</p>
            </div>
          )}

          {!pageQuery.isPending && pageQuery.data && currentUserError && includeSignerDetails && (
            <div
              className="pointer-events-none absolute w-max max-w-[55%] select-none rounded-sm bg-white/90 px-1 py-0.5 text-[10px] leading-tight text-destructive shadow-sm"
              style={{ left: `${box.x * 100}%`, top: `${(box.y + box.height) * 100}%`, marginTop: 2 }}
            >
              Could not load your profile to preview Name/Position/Department - the actual stamp isn&apos;t
              affected.
            </div>
          )}
        </div>
      </div>

      {info.alreadyPlaced && (
        <p className="mt-3 flex items-center justify-between gap-2 rounded-btn border border-[var(--gold)] bg-[var(--gold-tint)] px-3 py-2 text-[12.5px] text-[var(--gold-foreground)]">
          <span>This document already has a signature placed for this approval.</span>
          <button
            type="button"
            onClick={() => remove.mutate()}
            disabled={remove.isPending || place.isPending}
            className="flex shrink-0 items-center gap-1 font-semibold whitespace-nowrap underline disabled:cursor-not-allowed disabled:opacity-60"
          >
            {remove.isPending ? <Loader2 size={12} className="animate-spin" /> : <Trash2 size={12} />}
            Remove it
          </button>
        </p>
      )}

      {place.isError && (
        <p className="mt-3 rounded-btn border border-destructive/30 bg-destructive/10 px-3 py-2 text-[12.5px] text-destructive">
          {friendlyMessage(place.error, "Could not place your signature on the document.")}
        </p>
      )}

      {remove.isError && (
        <p className="mt-3 rounded-btn border border-destructive/30 bg-destructive/10 px-3 py-2 text-[12.5px] text-destructive">
          {friendlyMessage(remove.error, "Could not remove the placed signature.")}
        </p>
      )}

      <div className="mt-5 flex items-center justify-center gap-2.5">
        <button
          type="button"
          onClick={onBack}
          disabled={place.isPending}
          className="rounded-md border border-[var(--border-strong)] bg-card px-5 py-2.5 text-[13px] font-semibold text-foreground disabled:cursor-not-allowed disabled:opacity-60"
        >
          Back
        </button>
        <button
          type="button"
          onClick={() => place.mutate()}
          disabled={place.isPending || pageQuery.isPending}
          className={cn(
            "flex items-center gap-1.5 rounded-md border border-primary bg-primary px-5 py-2.5 text-[13px] font-bold tracking-[0.02em] text-primary-foreground disabled:cursor-not-allowed disabled:opacity-60"
          )}
        >
          {place.isPending && <Loader2 size={14} className="animate-spin" />}
          {info.alreadyPlaced ? "Replace signature" : "Place signature"}
        </button>
      </div>

      {preview.element}
    </div>
  )
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}
