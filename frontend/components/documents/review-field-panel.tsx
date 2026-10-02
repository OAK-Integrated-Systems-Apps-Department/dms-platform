"use client"

import { useState } from "react"
import { Check, Crosshair, Pencil, X } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { cn } from "@/lib/utils"

import type { ReviewField } from "@/lib/documents/api"

/** What the reviewer has decided about a field, if anything yet. */
export type FieldDecision = "accepted" | "corrected"

interface ReviewFieldPanelProps {
  fields: ReviewField[]
  decisions: Record<string, FieldDecision>
  /** Corrected values, keyed by field, shown in place of the OCR value. */
  values: Record<string, string>
  activeFieldKey: string | null
  onFocusField: (field: ReviewField) => void
  onAccept: (field: ReviewField) => void
  onEdit: (field: ReviewField, value: string) => void
  onReExtract: (field: ReviewField) => void
  /** The field currently waiting on a region selection, if any. */
  selectingFor: string | null
  busyFieldKey?: string | null
}

export function ReviewFieldPanel({
  fields,
  decisions,
  values,
  activeFieldKey,
  onFocusField,
  onAccept,
  onEdit,
  onReExtract,
  selectingFor,
  busyFieldKey,
}: ReviewFieldPanelProps) {
  if (fields.length === 0) {
    return (
      <p className="rounded-lg border border-border bg-card p-4 text-[13px] text-muted-foreground">
        No fields were requested for this document, so there is nothing to check.
      </p>
    )
  }

  return (
    <div className="flex flex-col gap-2">
      {fields.map((field) => (
        <FieldRow
          key={field.fieldKey}
          field={field}
          decision={decisions[field.fieldKey]}
          currentValue={values[field.fieldKey] ?? displayValue(field)}
          active={activeFieldKey === field.fieldKey}
          selecting={selectingFor === field.fieldKey}
          busy={busyFieldKey === field.fieldKey}
          onFocus={() => onFocusField(field)}
          onAccept={() => onAccept(field)}
          onEdit={(value) => onEdit(field, value)}
          onReExtract={() => onReExtract(field)}
        />
      ))}
    </div>
  )
}

/** A multi_select field comes back as an array; everything else as a string. */
export function displayValue(field: ReviewField): string {
  if (field.value === null || field.value === undefined) return ""
  return Array.isArray(field.value) ? field.value.join(", ") : field.value
}

/**
 * OCR's own `status` said in the reviewer's words, with the decision they
 * have already made taking precedence -- once a human has accepted a
 * value, "needs checking" is no longer true of it.
 */
export function statusBadge(
  field: ReviewField,
  decision?: FieldDecision
): { text: string; variant: "default" | "muted" | "gold" | "rust" } {
  if (decision === "corrected") return { text: "Corrected", variant: "default" }
  if (decision === "accepted") return { text: "Accepted", variant: "default" }
  if (field.status === "not_extractable") {
    // OCR never looked at this one -- it is not something a document
    // states (V44). "Not found" would send the reviewer hunting the page
    // for a classification level nobody ever printed on it. A mandatory
    // one still has to be filled in, so it stays rust; the difference is
    // that it asks the reviewer for the value rather than blaming OCR.
    return { text: field.mandatory ? "Enter manually" : "Not applicable", variant: field.mandatory ? "rust" : "muted" }
  }
  if (field.status === "defaulted") {
    // The value is real and will be filed; it just did not come off the
    // page. Never a review reason, so never gold -- but say where it came
    // from, because "Extracted" would be a lie about this document.
    return { text: "Default applied", variant: "muted" }
  }
  if (field.status === "not_found") {
    // A missing optional field is ordinary; a missing mandatory one is
    // the reason this document is in the queue.
    return { text: "Not found", variant: field.mandatory ? "rust" : "muted" }
  }
  if (field.status === "low_confidence") return { text: "Needs checking", variant: "gold" }
  return { text: "Extracted", variant: "muted" }
}

function FieldRow({
  field,
  decision,
  currentValue,
  active,
  selecting,
  busy,
  onFocus,
  onAccept,
  onEdit,
  onReExtract,
}: {
  field: ReviewField
  decision?: FieldDecision
  currentValue: string
  active: boolean
  selecting: boolean
  busy: boolean
  onFocus: () => void
  onAccept: () => void
  onEdit: (value: string) => void
  onReExtract: () => void
}) {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(currentValue)
  const badge = statusBadge(field, decision)

  function startEditing() {
    setDraft(currentValue)
    setEditing(true)
  }

  function commit() {
    setEditing(false)
    onEdit(draft)
  }

  return (
    <div
      onClick={onFocus}
      data-testid={`field-${field.fieldKey}`}
      className={cn(
        "rounded-lg border bg-card p-3 shadow-[var(--ledger-shadow)] transition-colors",
        field.source && "cursor-pointer",
        active ? "border-[var(--gold-foreground)]" : "border-border hover:border-[var(--border-strong)]"
      )}
    >
      <div className="mb-1.5 flex items-start justify-between gap-2">
        <span className="text-[12.5px] font-semibold text-foreground">
          {field.label}
          {field.mandatory && <span className="ml-1 text-[var(--rust)]">*</span>}
        </span>
        <Badge variant={badge.variant}>{badge.text}</Badge>
      </div>

      {editing ? (
        <div className="flex items-center gap-1.5">
          <Input
            autoFocus
            value={draft}
            aria-label={`${field.label} value`}
            onChange={(event) => setDraft(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") commit()
              if (event.key === "Escape") setEditing(false)
            }}
            className="h-8 text-[13px]"
          />
          <Button size="sm" variant="ghost" onClick={commit} aria-label={`Save ${field.label}`}>
            <Check size={15} />
          </Button>
          <Button size="sm" variant="ghost" onClick={() => setEditing(false)} aria-label="Cancel edit">
            <X size={15} />
          </Button>
        </div>
      ) : (
        <p className={cn("text-[13.5px]", currentValue ? "text-foreground" : "text-muted-foreground italic")}>
          {/* "nothing found" is a claim about the document, and it is
              false for a field OCR never read -- this one is empty
              because nobody prints it, not because the page was
              searched and came up short. */}
          {currentValue || (field.status === "not_extractable" ? "not read from the document" : "nothing found")}
        </p>
      )}

      {field.rawValue && !editing && (
        // Only shown when the parse changed it -- that difference is
        // where a wrong value usually comes from, and it is invisible if
        // only the parsed form is displayed.
        <p className="mt-1 text-[11.5px] text-muted-foreground">
          read as <span className="font-mono">{field.rawValue}</span>
        </p>
      )}

      <div className="mt-2 flex items-center justify-between gap-2">
        <span className="text-[11.5px] text-muted-foreground">
          {field.confidence != null
            ? `${Math.round(field.confidence * 100)}% confident`
            : "no confidence recorded"}
        </span>
        <div className="flex items-center gap-1">
          <Button
            size="sm"
            variant="ghost"
            onClick={onAccept}
            disabled={busy}
            aria-label={`Accept ${field.label}`}
          >
            <Check size={14} />
          </Button>
          <Button
            size="sm"
            variant="ghost"
            onClick={startEditing}
            disabled={busy}
            aria-label={`Edit ${field.label}`}
          >
            <Pencil size={14} />
          </Button>
          <Button
            size="sm"
            variant={selecting ? "default" : "ghost"}
            onClick={onReExtract}
            disabled={busy}
            aria-label={`Re-read ${field.label} from the page`}
          >
            <Crosshair size={14} />
          </Button>
        </div>
      </div>
    </div>
  )
}
