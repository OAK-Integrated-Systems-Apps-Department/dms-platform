"use client"

import { LoadFailed } from "@/components/ui/load-failed"
import { useMemo, useState } from "react"
import { useRouter } from "next/navigation"
import { useQueryClient } from "@tanstack/react-query"
import { ChevronLeft, ChevronRight } from "lucide-react"
import { toast } from "sonner"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { ReviewFieldPanel, displayValue, type FieldDecision } from "@/components/documents/review-field-panel"
import { ReviewLineItems } from "@/components/documents/review-line-items"
import { ReviewPageViewer, type Highlight } from "@/components/documents/review-page-viewer"
import { isRetryable } from "@/components/documents/document-upload-form"
import { BackendUnavailable } from "@/components/ui/backend-unavailable"
import { Button } from "@/components/ui/button"
import { LoadingState } from "@/components/ui/loading-state"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { getTooltip } from "@/lib/tooltips"
import { isNetworkError } from "@/lib/api-error"
import {
  readRegion,
  rerunOcr,
  saveReview,
  type DocumentReview,
  type ReviewDecision,
  type ReviewField,
} from "@/lib/documents/api"
import { useDocumentReview, usePageImage } from "@/lib/documents/use-document-review"
import { usePageTitle } from "@/lib/use-page-title"

export function DocumentReviewContent({ documentId }: { documentId: string }) {
  usePageTitle("Review document")

  const { data, isPending, isError, error, refetch } = useDocumentReview(documentId)
  const [page, setPage] = useState(0)
  const { image, isLoading: imageLoading, error: imageError } = usePageImage(documentId, page)

  const [activeFieldKey, setActiveFieldKey] = useState<string | null>(null)
  const [selectingFor, setSelectingFor] = useState<string | null>(null)
  const [busyFieldKey, setBusyFieldKey] = useState<string | null>(null)
  const [decisions, setDecisions] = useState<Record<string, FieldDecision>>({})
  const [values, setValues] = useState<Record<string, string>>({})
  // How a correction was produced. Kept separate from `values` because
  // the backend records it as its own fact: a region correction says OCR
  // could read the text once pointed at the right part of the page,
  // which is a different defect from OCR misreading it.
  const [sources, setSources] = useState<Record<string, "typed" | "region">>({})
  const [saving, setSaving] = useState(false)
  const [retrying, setRetrying] = useState(false)

  const router = useRouter()
  const queryClient = useQueryClient()

  // Only the fields whose value is actually on THIS page get a box. A
  // field's source.page is 1-based (it comes from the OCR output); the
  // viewer is 0-based.
  const highlights: Highlight[] = useMemo(() => {
    if (!data) return []
    return data.fields
      .filter((field) => field.source && field.source.page - 1 === page)
      .map((field) => ({
        key: field.fieldKey,
        bbox: field.source!.bbox,
        active: field.fieldKey === activeFieldKey,
        label: field.label,
      }))
  }, [data, page, activeFieldKey])

  function focusField(field: ReviewField) {
    setActiveFieldKey(field.fieldKey)
    if (!field.source) return
    // Clicking a field scrolls the preview to its region: the page first,
    // then the box itself once that page's boxes exist.
    const targetPage = field.source.page - 1
    if (targetPage !== page) setPage(targetPage)
    requestAnimationFrame(() => {
      document
        .querySelector(`[data-testid="highlight-${field.fieldKey}"]`)
        ?.scrollIntoView({ block: "center", behavior: "smooth" })
    })
  }

  function focusFromHighlight(fieldKey: string) {
    setActiveFieldKey(fieldKey)
    document
      .querySelector(`[data-testid="field-${fieldKey}"]`)
      ?.scrollIntoView({ block: "center", behavior: "smooth" })
  }

  function accept(field: ReviewField) {
    setDecisions((current) => {
      // Pressing Accept on a field you have ALREADY corrected means "yes,
      // my value is right" -- not "throw it away and take OCR's".
      //
      // Downgrading the decision to `accepted` here sent
      // `value: null` (see `save`), so the typed value never left the
      // browser, while the card went on displaying it under an
      // "Accepted" badge. Measured live on a mandatory dropdown: typed
      // "internal", pressed Accept, card read `Accepted internal`, the
      // request carried `{"action":"accepted","value":null}`, and the
      // document stayed in the queue for a field the screen showed as
      // filled and confirmed.
      if (current[field.fieldKey] === "corrected") return current
      return { ...current, [field.fieldKey]: "accepted" }
    })
  }

  function edit(field: ReviewField, value: string) {
    const original = values[field.fieldKey] ?? displayValue(field)
    if (value === original) {
      // Opening the editor and closing it unchanged is not a correction.
      // Recording it as one would poison the very measurement that
      // corrections exist to produce.
      setDecisions((current) => ({ ...current, [field.fieldKey]: "accepted" }))
      return
    }
    setValues((current) => ({ ...current, [field.fieldKey]: value }))
    setDecisions((current) => ({ ...current, [field.fieldKey]: "corrected" }))
    setSources((current) => ({ ...current, [field.fieldKey]: "typed" }))
  }

  function startReExtract(field: ReviewField) {
    setActiveFieldKey(field.fieldKey)
    setSelectingFor((current) => (current === field.fieldKey ? null : field.fieldKey))
    if (field.source && field.source.page - 1 !== page) setPage(field.source.page - 1)
  }

  async function applyRegion(bbox: [number, number, number, number]) {
    const fieldKey = selectingFor
    setSelectingFor(null)
    if (!fieldKey) return

    setBusyFieldKey(fieldKey)
    try {
      const reading = await readRegion(documentId, page, bbox)
      const text = reading.text.trim()
      if (!text) {
        toast.error("Nothing readable in that area. Try selecting a bit more of the line.")
        return
      }
      setValues((current) => ({ ...current, [fieldKey]: text }))
      setDecisions((current) => ({ ...current, [fieldKey]: "corrected" }))
      setSources((current) => ({ ...current, [fieldKey]: "region" }))
      toast.success(`Read "${text}" from the page.`)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "Could not read that part of the page.")
    } finally {
      setBusyFieldKey(null)
    }
  }

  async function save() {
    const payload: ReviewDecision[] = Object.entries(decisions).map(([fieldKey, action]) => ({
      fieldKey,
      action,
      value: action === "corrected" ? (values[fieldKey] ?? "") : null,
      source: action === "corrected" ? (sources[fieldKey] ?? "typed") : null,
    }))
    if (payload.length === 0) {
      toast.error("Accept or correct at least one field before saving.")
      return
    }

    setSaving(true)
    try {
      const result = await saveReview(documentId, payload)
      // The queue's contents just changed, and the document's own review
      // state with it -- both caches are stale the moment this returns.
      // Saved Reviews is stale too: every save either adds this document to
      // that list for the first time, or changes the resolved/unresolved
      // state it shows there.
      await queryClient.invalidateQueries({ queryKey: ["review-queue"] })
      await queryClient.invalidateQueries({ queryKey: ["saved-reviews"] })
      await queryClient.invalidateQueries({ queryKey: ["document-review", documentId] })

      if (result.requiresReview) {
        // Still queued, and saying why beats a success message that
        // contradicts the document still being in the list.
        //
        // NAME the fields. The server has always returned them in
        // `remainingReasons` and this only ever showed the count, which
        // made the common case unreadable: a reviewer who pressed Accept
        // on every field was told "1 field still needs attention" with no
        // way to tell which, and no hint that the blocker is a mandatory
        // field with no VALUE -- something accepting can never settle,
        // because accepting an empty mandatory field agrees that it is
        // empty (see ReviewDecisionApplier.unsettled). Measured live:
        // 8 fields accepted, 8 recorded, still queued on
        // `classification_level`.
        //
        // `remainingReasons` carries field KEYS; reviewers know labels.
        const stillOpen = result.remainingReasons.map((key) => {
          const field = data?.fields.find((f) => f.fieldKey === key)
          // The reviewer's own edit wins over OCR's value: they may have
          // just typed into a field the server still counts as open.
          const current = values[key] ?? (field ? displayValue(field) : "")
          return { label: field?.label ?? key, needsValue: Boolean(field?.mandatory) && !current.trim() }
        })
        const names = stillOpen.map((f) => f.label).join(", ")
        // "needs a value" when it is empty and required -- that is an
        // instruction the reviewer can act on. "Needs attention" is not.
        const allNeedValues = stillOpen.length > 0 && stillOpen.every((f) => f.needsValue)
        const what = allNeedValues
          ? stillOpen.length === 1 ? "still needs a value" : "still need values"
          : stillOpen.length === 1 ? "still needs attention" : "still need attention"
        toast.warning(`Saved. ${names} ${what}, so this stays in the queue.`)
        return
      }
      toast.success("Review saved. This document has left the queue.")
      router.push("/documents/review")
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "Could not save your review.")
    } finally {
      setSaving(false)
    }
  }

  async function retryOcr() {
    if (!data) return
    setRetrying(true)
    try {
      await rerunOcr(data.versionId, [])
      await queryClient.invalidateQueries({ queryKey: ["document-review", documentId] })
      toast.success("OCR is running again. This page will update when it finishes.")
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "Could not re-run OCR on this document.")
    } finally {
      setRetrying(false)
    }
  }

  const decisionCount = Object.keys(decisions).length

  if (isPending) {
    return (
      <DashboardShell initialActiveLabel="Review Queue">
        <LoadingState message="Loading the document…" />
      </DashboardShell>
    )
  }

  if (isError || !data) {
    return (
      <DashboardShell initialActiveLabel="Review Queue">
        {isNetworkError(error) ? (
          <BackendUnavailable onRetry={refetch} />
        ) : (
          <LoadFailed error={error} fallback="Could not load this document." />
        )}
      </DashboardShell>
    )
  }

  return (
    <DashboardShell initialActiveLabel="Review Queue">
      <AdminBreadcrumb
        className="mb-3"
        segments={[
          { label: "Dashboard", href: "/" },
          { label: "Review Queue", href: "/documents/review" },
          { label: data.title },
        ]}
      />

      <ReviewHeader review={data} onRetry={retryOcr} retrying={retrying} />

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,1fr)_360px]">
        <div className="flex flex-col gap-3">
          <PageControls
            page={page}
            pageCount={image?.pageCount ?? data.pageCount}
            onChange={setPage}
          />
          <ReviewPageViewer
            imageUrl={image?.url ?? null}
            isLoading={imageLoading}
            error={imageError}
            highlights={highlights}
            onHighlightClick={focusFromHighlight}
            selecting={selectingFor !== null}
            onRegionSelected={applyRegion}
            onCancelSelection={() => setSelectingFor(null)}
          />
          <ReviewLineItems items={data.lineItems} onJumpToPage={setPage} />
        </div>

        <div className="flex flex-col gap-2">
          <ReviewFieldPanel
            fields={data.fields}
            decisions={decisions}
            values={values}
            activeFieldKey={activeFieldKey}
            onFocusField={focusField}
            onAccept={accept}
            onEdit={edit}
            onReExtract={startReExtract}
            selectingFor={selectingFor}
            busyFieldKey={busyFieldKey}
          />

          <div className="sticky bottom-3 mt-1 rounded-lg border border-border bg-card p-3 shadow-[var(--ledger-shadow)]">
            <p className="mb-2 text-[12px] text-muted-foreground">
              {decisionCount === 0
                ? "Accept or correct a field to record your review."
                : `${decisionCount} field${decisionCount === 1 ? "" : "s"} decided.`}
            </p>
            <Tooltip>
              {/* The trigger is a plain span, not the Button: a disabled
                  button fires no pointer events, so wrapping it directly
                  would hide the tooltip in exactly the state whose
                  explanation the reviewer needs. */}
              <TooltipTrigger
                render={(props) => (
                  <span {...props} className="block">
                    <Button className="w-full" onClick={save} disabled={saving || decisionCount === 0}>
                      {saving ? "Saving…" : "Save review"}
                    </Button>
                  </span>
                )}
              />
              <TooltipContent>
                {decisionCount === 0
                  ? getTooltip("documents.review.saveDisabled")
                  : getTooltip("documents.review.save")}
              </TooltipContent>
            </Tooltip>
          </div>
        </div>
      </div>
    </DashboardShell>
  )
}

function ReviewHeader({
  review,
  onRetry,
  retrying,
}: {
  review: DocumentReview
  onRetry: () => void
  retrying: boolean
}) {
  const failed = review.ocrStatus === "failed"
  return (
    <div className="mb-[18px]">
      <h1 className="mb-1 font-heading text-[24px] font-semibold tracking-[-0.01em]">{review.title}</h1>
      <p className="mb-2 text-[12.5px] text-muted-foreground">{review.originalFilename}</p>
      {(failed || review.requiresReview) && (
        <div
          className={
            failed
              ? "rounded-lg border border-[var(--rust)] bg-[var(--rust-tint)] p-3 text-[13px] text-[var(--rust)]"
              : "rounded-lg border border-[var(--gold-foreground)] bg-[var(--gold-tint)] p-3 text-[13px] text-[var(--gold-foreground)]"
          }
        >
          {/* The same sentence the queue row showed, deliberately: it is
              built by the same mapper server-side, so the reason a
              reviewer clicked through cannot disagree with the reason
              they then read. */}
          {review.friendlyError ?? review.reviewSummary}
          {failed && isRetryable(review.errorCode) && (
            // A failed document in the queue with nothing to click is a
            // dead end: the reviewer can see what went wrong and do
            // nothing about it. A format we cannot read will never
            // succeed on a retry, so that case gets no button rather
            // than a false promise.
            <div className="mt-2">
              <Button size="sm" variant="outline" onClick={onRetry} disabled={retrying}>
                {retrying ? "Starting…" : "Try OCR again"}
              </Button>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

function PageControls({
  page,
  pageCount,
  onChange,
}: {
  page: number
  pageCount: number
  onChange: (page: number) => void
}) {
  if (pageCount <= 1) return null
  return (
    <div className="flex items-center gap-2">
      <Button
        size="sm"
        variant="outline"
        disabled={page === 0}
        onClick={() => onChange(page - 1)}
        aria-label="Previous page"
      >
        <ChevronLeft size={15} />
      </Button>
      <span className="text-[12.5px] text-muted-foreground">
        Page {page + 1} of {pageCount}
      </span>
      <Button
        size="sm"
        variant="outline"
        disabled={page >= pageCount - 1}
        onClick={() => onChange(page + 1)}
        aria-label="Next page"
      >
        <ChevronRight size={15} />
      </Button>
    </div>
  )
}
