"use client"

import { LoadFailed } from "@/components/ui/load-failed"
import { useRef, useState } from "react"
import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query"
import { Eye, FilePlus2, Loader2, Trash2 } from "lucide-react"
import { toast } from "sonner"

import { useCurrentUser } from "@/lib/auth/use-current-user"
import { type DocumentDto } from "@/lib/documents/api"
import {
  addDocumentSignaturePage,
  fetchDocumentSignaturePlacementInfo,
  fetchDocumentSignaturePlacementPage,
  placeDocumentSignature,
  removeDocumentSignaturePlacement,
  type VisualSignatureKind,
} from "@/lib/signatures/api"
import { cn, formatSignatureStampDate } from "@/lib/utils"

import { Checkbox } from "@/components/ui/checkbox"

import { useDocumentPreview } from "@/components/workflow/use-document-preview"

interface DocumentSignaturePlacementStepProps {
  document: DocumentDto
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
 * Standalone-signing equivalent of `SignaturePlacementStep` - same drag-to-place UX, but keyed off a
 * document directly instead of a workflow task (see `StandaloneSignaturePlacementService`'s own doc
 * comment on the backend for why this is a parallel class rather than a taskId-or-documentId union
 * threaded through the original). The one addition: "Add signature page" for when no existing page has
 * room - there's no reliable way to detect that automatically, so it's an explicit choice, not inferred.
 */
export function DocumentSignaturePlacementStep({
  document,
  visualSignatureKind,
  visualSignatureText,
  visualSignatureImageBase64,
  onBack,
  onDone,
}: DocumentSignaturePlacementStepProps) {
  const documentId = document.documentId
  const queryClient = useQueryClient()
  const preview = useDocumentPreview()
  const { data: currentUser, isError: currentUserError } = useCurrentUser()
  const infoKey = ["signature-placement-info", "document", documentId]
  const { data: info, isPending: infoPending } = useQuery({
    queryKey: infoKey,
    queryFn: () => fetchDocumentSignaturePlacementInfo(documentId),
  })
  const [page, setPage] = useState(0)
  const [box, setBox] = useState({ x: 0.36, y: 0.75, width: DEFAULT_WIDTH_RATIO, height: DEFAULT_HEIGHT_RATIO })
  const [includeSignerDetails, setIncludeSignerDetails] = useState(true)
  const pageRefs = useRef(new Map<number, HTMLDivElement>())
  const dragRef = useRef<{ startX: number; startY: number; boxX: number; boxY: number } | null>(null)

  // Every page is fetched and shown at once, stacked in one scrollable reading pane - like Word's
  // continuous view, rather than flipping through pages one at a time. staleTime/gcTime: Infinity -
  // see SignaturePlacementStep's own comment on why (this component remounts across step visits the
  // same way).
  const pageCount = info?.pageCount ?? 0
  const pageQueries = useQueries({
    queries: Array.from({ length: pageCount }, (_, i) => ({
      queryKey: ["signature-placement-page", "document", documentId, i],
      queryFn: async () => URL.createObjectURL(await fetchDocumentSignaturePlacementPage(documentId, i, PAGE_WIDTH)),
      enabled: Boolean(info?.supported),
      staleTime: Infinity,
      gcTime: Infinity,
    })),
  })
  const anyPagePending = pageQueries.some((q) => q.isPending)

  const addPage = useMutation({
    mutationFn: () => addDocumentSignaturePage(documentId),
    onSuccess: (updated) => {
      queryClient.setQueryData(infoKey, updated)
      setPage(updated.pageCount - 1)
    },
  })

  const place = useMutation({
    mutationFn: () =>
      placeDocumentSignature(documentId, {
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
      queryClient.invalidateQueries({ queryKey: ["signatures", documentId] })
      onDone()
    },
  })

  const remove = useMutation({
    mutationFn: () => removeDocumentSignaturePlacement(documentId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: infoKey })
      queryClient.invalidateQueries({ queryKey: ["signatures", documentId] })
      toast.success("Signature removed - you can place it again.")
    },
  })

  function startDrag(event: React.PointerEvent<HTMLDivElement>) {
    event.stopPropagation()
    event.currentTarget.setPointerCapture(event.pointerId)
    dragRef.current = { startX: event.clientX, startY: event.clientY, boxX: box.x, boxY: box.y }
  }

  /**
   * Repositioning is drag-only - there used to also be a "click anywhere on a page to teleport the
   * mark there" shortcut, meant for moving the mark between pages in this continuous-scroll view, but
   * it fired on every incidental click anywhere on any page (scrolling by clicking the pane, selecting
   * text to read, clicking a different page just to look at it) and silently relocated an already-placed
   * signature to wherever that click happened to land - the exact "my signature keeps jumping/ends up
   * somewhere random" complaint this replaced. Dragging across a page boundary (pages are stacked
   * vertically in one pane, so this is just "keep holding and move the pointer up/down past the edge")
   * now re-anchors the drag onto whichever page the pointer is actually over, which covers the same
   * need - moving the mark to a different page - without any click elsewhere ever moving it by itself.
   */
  function onDrag(event: React.PointerEvent<HTMLDivElement>) {
    const drag = dragRef.current
    if (!drag) return

    let targetPage = page
    let targetRect: DOMRect | null = null
    for (const [index, element] of pageRefs.current.entries()) {
      const rect = element.getBoundingClientRect()
      if (event.clientY >= rect.top && event.clientY <= rect.bottom) {
        targetPage = index
        targetRect = rect
        break
      }
    }
    // The pointer strayed into the gap between pages (or above/below all of them) - keep dragging on
    // whichever page was last active rather than losing the drag entirely.
    if (!targetRect) {
      const container = pageRefs.current.get(page)
      if (!container) return
      targetRect = container.getBoundingClientRect()
    }

    if (targetPage !== page) {
      const x = clamp((event.clientX - targetRect.left) / targetRect.width - box.width / 2, 0, 1 - box.width)
      const y = clamp((event.clientY - targetRect.top) / targetRect.height - box.height / 2, 0, 1 - box.height)
      setPage(targetPage)
      setBox((prev) => ({ ...prev, x, y }))
      dragRef.current = { startX: event.clientX, startY: event.clientY, boxX: x, boxY: y }
      return
    }

    const dx = (event.clientX - drag.startX) / targetRect.width
    const dy = (event.clientY - drag.startY) / targetRect.height
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
          This file type can&apos;t be marked up directly - your signature will still be recorded, just not drawn
          onto the page itself.
        </p>
        <div className="flex items-center justify-center gap-2.5">
          <button
            type="button"
            onClick={onBack}
            className="border border-[var(--border-strong)] bg-card font-semibold text-foreground h-[38px] rounded-btn px-4 text-[13px] inline-flex items-center justify-center"
          >
            Back
          </button>
          <button
            type="button"
            onClick={onDone}
            className="border border-primary bg-primary text-primary-foreground h-[38px] rounded-btn px-4 text-[13px] font-semibold inline-flex items-center justify-center"
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
        <div className="flex shrink-0 items-center gap-2">
          <button
            type="button"
            onClick={() => preview.open(document)}
            className="flex items-center gap-1.5 whitespace-nowrap border border-[var(--border-strong)] text-foreground hover:bg-muted h-[38px] rounded-btn px-4 text-[13px] font-semibold justify-center"
          >
            <Eye size={13} />
            Read document
          </button>
          <button
            type="button"
            onClick={() => addPage.mutate()}
            disabled={addPage.isPending || place.isPending}
            className="flex items-center gap-1.5 whitespace-nowrap border border-[var(--border-strong)] text-foreground hover:bg-muted disabled:cursor-not-allowed disabled:opacity-60 h-[38px] rounded-btn px-4 text-[13px] font-semibold justify-center"
          >
            {addPage.isPending ? <Loader2 size={13} className="animate-spin" /> : <FilePlus2 size={13} />}
            Add signature page
          </button>
        </div>
      </div>

      <div className="mb-3 rounded-btn border border-[var(--border-strong)] px-3 py-1.5">
        <Checkbox
          id="sign-document-include-details"
          label="Include my name, designation, department and date"
          hint="Turn off to stamp just the signature mark - your identity and signing time stay in the cryptographic signature either way."
          checked={includeSignerDetails}
          onCheckedChange={setIncludeSignerDetails}
        />
      </div>

      {addPage.isError && (
        <LoadFailed error={addPage.error} fallback="Could not add a page to this document." quiet />
      )}

      {info.pageCount > 1 && (
        <p className="mb-2 text-center text-[12px] font-semibold text-muted-foreground">
          Signature on page {page + 1} of {info.pageCount} - drag it onto a different page below to move it
        </p>
      )}

      {/* Every page stacked in one scrollable pane, like Word's continuous view, rather than one page
          flipped at a time - see SignaturePlacementStep's own comment on the shrink-wrap/centering
          approach each page box uses. */}
      <div className="flex max-h-[78vh] flex-col items-center gap-3 overflow-auto rounded-btn border border-[var(--border-strong)] bg-muted/40 p-3">
        {pageQueries.map((pageQuery, i) => (
          <div key={i} className="relative inline-block shrink-0">
            <div
              ref={(el) => {
                if (el) pageRefs.current.set(i, el)
                else pageRefs.current.delete(i)
              }}
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
                  alt={`Page ${i + 1}`}
                  className="block max-h-[78vh] w-auto max-w-full select-none"
                  draggable={false}
                />
              ) : (
                <p className="p-6 text-center text-[12.5px] text-destructive">Could not load this page.</p>
              )}

              {page === i && pageQuery.data && (
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

              {/* Approximate preview only - see SignaturePlacementStep's own comment on why (fixed
                  black-on-white, never `text-foreground` - this sits on the rendered page, always a
                  light image regardless of the app's own theme). */}
              {page === i && pageQuery.data && currentUser && includeSignerDetails && (
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

              {page === i && pageQuery.data && currentUserError && includeSignerDetails && (
                <div
                  className="pointer-events-none absolute w-max max-w-[55%] select-none rounded-sm bg-white/90 px-1 py-0.5 text-[10px] leading-tight text-destructive shadow-sm"
                  style={{ left: `${box.x * 100}%`, top: `${(box.y + box.height) * 100}%`, marginTop: 2 }}
                >
                  Could not load your profile to preview Name/Position/Department - the actual stamp
                  isn&apos;t affected.
                </div>
              )}
            </div>
          </div>
        ))}
      </div>

      {info.alreadyPlaced && (
        <p className="mt-3 flex items-center justify-between gap-2 rounded-btn border border-[var(--gold)] bg-[var(--gold-tint)] px-3 py-2 text-[12.5px] text-[var(--gold-foreground)]">
          <span>This document already has a standalone signature placed on it.</span>
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
        <LoadFailed error={place.error} fallback="Could not place your signature on the document." quiet />
      )}

      {remove.isError && (
        <LoadFailed error={remove.error} fallback="Could not remove the placed signature." quiet />
      )}

      <div className="mt-5 flex items-center justify-center gap-2.5">
        <button
          type="button"
          onClick={onBack}
          disabled={place.isPending}
          className="border border-[var(--border-strong)] bg-card font-semibold text-foreground disabled:cursor-not-allowed disabled:opacity-60 h-[38px] rounded-btn px-4 text-[13px] inline-flex items-center justify-center"
        >
          Back
        </button>
        <button
          type="button"
          onClick={() => place.mutate()}
          disabled={place.isPending || anyPagePending}
          className={cn(
            "flex items-center gap-1.5 border border-primary bg-primary text-primary-foreground disabled:cursor-not-allowed disabled:opacity-60 h-[38px] rounded-btn px-4 text-[13px] font-semibold justify-center"
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
