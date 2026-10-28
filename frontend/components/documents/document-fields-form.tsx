"use client"

import { Lock } from "lucide-react"

import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import type { DocumentTypeSchemaResponseDto } from "@/lib/document-type-fields/api"
import { getTooltip } from "@/lib/tooltips"
import { cn } from "@/lib/utils"
import type { MetadataValues } from "@/lib/documents/api"

type SchemaField = DocumentTypeSchemaResponseDto["fields"][number]

export function visibleFieldsOf(schema: DocumentTypeSchemaResponseDto | undefined): SchemaField[] {
  return (schema?.fields ?? []).filter((f) => !f.hidden).sort((a, b) => a.displayOrder - b.displayOrder)
}

/** Every mandatory, visible field's key -- the floor "Clear" resets a field selection to (see `FieldChips`'s "Clear" action). */
export function mandatoryRequestedFields(schema: DocumentTypeSchemaResponseDto | undefined): Set<string> {
  return new Set(visibleFieldsOf(schema).filter((f) => f.mandatory).map((f) => f.fieldKey))
}

/**
 * A document type's default OCR field selection: every mandatory field
 * (always selected, can't be turned off -- see `FieldChips`) plus any
 * field the schema itself flags `ocrExtractable` (a hint from the field
 * catalogue that OCR is expected to be able to fill it in). Exported so
 * `DocumentUploadForm` can compute this both on first load and whenever
 * `documentType` changes.
 */
export function defaultRequestedFields(schema: DocumentTypeSchemaResponseDto | undefined): Set<string> {
  return new Set(visibleFieldsOf(schema).filter((f) => f.mandatory || f.ocrExtractable).map((f) => f.fieldKey))
}

/**
 * Reconciles a prior field selection against a newly-loaded schema (e.g.
 * the user switched Document Type after already picking fields): the new
 * type's own defaults, plus whichever previously-selected field keys are
 * still visible on the new schema. Never drops a mandatory field, since
 * `defaultRequestedFields` already includes every one of those.
 */
export function reconcileRequestedFields(
  previous: Set<string>,
  schema: DocumentTypeSchemaResponseDto | undefined
): Set<string> {
  const visibleKeys = new Set(visibleFieldsOf(schema).map((f) => f.fieldKey))
  const next = defaultRequestedFields(schema)
  for (const key of previous) {
    if (visibleKeys.has(key)) next.add(key)
  }
  return next
}

/**
 * Renders one input per visible field in a document type's resolved schema
 * (`GET /document-types/{id}/schema` -- MDTM-FR-006, "for the upload form
 * ... once those modules exist": this is that upload form). A field this
 * document's type marks `ocrExtractable` gets a small "OCR" badge, since
 * Run OCR can fill it in automatically if left blank (see
 * DocumentMetadataService#applyOcrFields on the backend) -- manual entry
 * here always wins over whatever OCR would have filled in.
 *
 * `user_ref`/`lookup` fields render as plain text -- there's no picker UI
 * yet to resolve a real reference, matching the same simplification
 * DocumentMetadataServiceImpl documents on its own value-storage side.
 */
/** One field's latest OCR extraction attempt -- see `lib/documents/api.ts#OcrFieldResult`. Kept as a narrow local shape so this component doesn't need to import the full `lib/documents/api.ts` module just for a type. */
export interface FieldOcrBadgeInfo {
  confidence: number | null
  status: "extracted" | "not_found" | "low_confidence" | "not_extractable" | "defaulted"
}

export function DocumentFieldsForm({
  schema,
  loading,
  values,
  onChange,
  disabled,
  ocrResults,
  onRerunField,
}: {
  schema: DocumentTypeSchemaResponseDto | undefined
  loading: boolean
  values: MetadataValues
  onChange: (fieldKey: string, value: string | string[]) => void
  disabled?: boolean
  /** Present once a document's OCR pass has completed -- drives the small confidence badge next to each requested field's label. */
  ocrResults?: Record<string, FieldOcrBadgeInfo>
  /** Omit to hide the per-field "Re-run" affordance entirely (e.g. before any OCR pass has run yet). */
  onRerunField?: (fieldKey: string) => void
}) {
  if (loading) {
    return <p className="text-[12.5px] text-muted-foreground">Loading fields…</p>
  }

  const fields = visibleFieldsOf(schema)
  if (fields.length === 0) {
    return null
  }

  return (
    <div className="grid grid-cols-2 gap-[18px]">
      {fields.map((field) => {
        const id = `field-${field.fieldKey}`
        const ocrResult = ocrResults?.[field.fieldKey]
        return (
          <div key={field.fieldId}>
            <Label htmlFor={id} className="flex items-center gap-1.5">
              <span>
                {field.label}
                {field.mandatory && <span className="text-[var(--rust)]"> *</span>}
              </span>
              {field.ocrExtractable && !ocrResult && (
                <Tooltip>
                  <TooltipTrigger
                    render={(props) => (
                      <span
                        {...props}
                        className="rounded-full border border-border px-1.5 py-0.5 text-[10px] font-normal normal-case text-muted-foreground"
                      >
                        OCR
                      </span>
                    )}
                  />
                  <TooltipContent>{getTooltip("documents.ocr.badge")}</TooltipContent>
                </Tooltip>
              )}
              {ocrResult && <OcrResultBadge result={ocrResult} />}
              {/* No "Re-run" on a `not_extractable` field: re-running it
                  runs the same skip and returns the same row, so the link
                  can only ever waste a round trip and suggest the value is
                  one more attempt away. */}
              {ocrResult && ocrResult.status !== "not_extractable" && onRerunField && (
                <Tooltip>
                  <TooltipTrigger
                    render={(props) => (
                      <button
                        {...props}
                        type="button"
                        disabled={disabled}
                        onClick={() => onRerunField(field.fieldKey)}
                        aria-label={`Re-run OCR for ${field.label}`}
                        className="text-[10.5px] font-semibold text-primary hover:underline disabled:cursor-not-allowed disabled:opacity-60"
                      >
                        Re-run
                      </button>
                    )}
                  />
                  <TooltipContent>{getTooltip("documents.ocr.rerunField")}</TooltipContent>
                </Tooltip>
              )}
            </Label>
            <FieldInput id={id} field={field} value={values[field.fieldKey]} onChange={onChange} disabled={disabled} />
            {field.helpText && <p className="mt-1.5 text-[12px] text-muted-foreground">{field.helpText}</p>}
          </div>
        )
      })}
    </div>
  )
}

/**
 * Small status badge for one field's latest OCR attempt -- `not_found` and
 * `low_confidence` are visually distinguished from a plain successful
 * `extracted` so the user knows what to double-check.
 *
 * `not_extractable` gets no badge at all, deliberately: the field was never
 * sent to OCR (V44 says a document does not state it), so an OCR badge of
 * any colour on it is noise at best and a claim OCR tried and failed at
 * worst. The chip picker already renders these fields without the "OCR"
 * tag, so a badge appearing afterwards would contradict it.
 */
function OcrResultBadge({ result }: { result: FieldOcrBadgeInfo }) {
  if (result.status === "not_extractable") {
    return null
  }

  // Text and styling resolved first, then wrapped once. Returning a
  // separate <Tooltip> from each branch meant four copies of the same
  // wrapper, which is how one of them ends up without a hint.
  const pct = result.confidence != null ? Math.round(result.confidence * 100) : null
  const badge =
    result.status === "defaulted"
      ? {
          // Not an accuracy claim: nothing on the page said this, the
          // tenant default did. Said plainly so a reviewer who disagrees
          // knows it is a setting they can change rather than a misread
          // they must correct.
          text: "Default applied",
          hint: getTooltip("documents.status.defaulted"),
          className: "border-border text-muted-foreground font-normal",
        }
      : result.status === "not_found"
        ? {
            text: "Not found",
            hint: getTooltip("documents.status.notFound"),
            className:
              "border-[var(--rust)]/40 bg-[var(--rust-tint)] text-[var(--rust-foreground)] font-semibold",
          }
        : result.status === "low_confidence"
          ? {
              text: `Low confidence${pct != null ? ` · ${pct}%` : ""}`,
              hint: getTooltip("documents.status.lowConfidence"),
              className: "border-[var(--rust)]/30 text-[var(--rust)] font-semibold",
            }
          : {
              text: pct != null ? `${pct}%` : "OCR",
              hint: getTooltip("documents.status.extracted"),
              className: "border-border text-muted-foreground font-normal",
            }

  return (
    <Tooltip>
      <TooltipTrigger
        render={(props) => (
          <span
            {...props}
            className={cn("rounded-full border px-1.5 py-0.5 text-[10px] normal-case", badge.className)}
          >
            {badge.text}
          </span>
        )}
      />
      <TooltipContent>{badge.hint}</TooltipContent>
    </Tooltip>
  )
}

function FieldInput({
  id,
  field,
  value,
  onChange,
  disabled,
}: {
  id: string
  field: DocumentTypeSchemaResponseDto["fields"][number]
  value: string | string[] | undefined
  onChange: (fieldKey: string, value: string | string[]) => void
  disabled?: boolean
}) {
  const stringValue = typeof value === "string" ? value : ""
  const selectClassName =
    "h-10 w-full appearance-none rounded-btn border border-[var(--border-strong)] bg-muted px-3 text-[13.5px] text-foreground outline-none focus-visible:border-primary focus-visible:bg-card disabled:opacity-70"

  switch (field.dataType) {
    case "boolean":
      return (
        <div className="flex h-10 items-center">
          <input
            id={id}
            type="checkbox"
            disabled={disabled}
            checked={stringValue === "true"}
            onChange={(e) => onChange(field.fieldKey, e.target.checked ? "true" : "false")}
            className="size-4"
          />
        </div>
      )

    case "long_text":
      return (
        <textarea
          id={id}
          disabled={disabled}
          value={stringValue}
          onChange={(e) => onChange(field.fieldKey, e.target.value)}
          rows={3}
          className="w-full rounded-btn border border-[var(--border-strong)] bg-muted px-3 py-2 text-[13.5px] text-foreground outline-none focus-visible:border-primary focus-visible:bg-card disabled:opacity-70"
        />
      )

    case "number":
    case "decimal":
      return (
        <Input id={id} type="number" disabled={disabled} value={stringValue} onChange={(e) => onChange(field.fieldKey, e.target.value)} />
      )

    case "date":
      return <Input id={id} type="date" disabled={disabled} value={stringValue} onChange={(e) => onChange(field.fieldKey, e.target.value)} />

    case "datetime":
      return (
        <Input
          id={id}
          type="datetime-local"
          disabled={disabled}
          value={stringValue}
          onChange={(e) => onChange(field.fieldKey, e.target.value)}
        />
      )

    case "dropdown":
      return (
        <select id={id} disabled={disabled} value={stringValue} onChange={(e) => onChange(field.fieldKey, e.target.value)} className={selectClassName}>
          <option value="">—</option>
          {field.options.map((o) => (
            <option key={o.optionId} value={o.optionValue}>
              {o.optionLabel}
            </option>
          ))}
        </select>
      )

    case "multi_select": {
      const selected = Array.isArray(value) ? value : []
      return (
        <div className="flex flex-wrap gap-x-3 gap-y-1.5 rounded-btn border border-[var(--border-strong)] bg-muted px-3 py-2.5">
          {field.options.length === 0 && <span className="text-[12.5px] text-muted-foreground">No options configured.</span>}
          {field.options.map((o) => (
            <label key={o.optionId} className="flex items-center gap-1.5 text-[12.5px] text-foreground">
              <input
                type="checkbox"
                disabled={disabled}
                checked={selected.includes(o.optionValue)}
                onChange={(e) => {
                  const next = e.target.checked ? [...selected, o.optionValue] : selected.filter((v) => v !== o.optionValue)
                  onChange(field.fieldKey, next)
                }}
              />
              {o.optionLabel}
            </label>
          ))}
        </div>
      )
    }

    default:
      // text, user_ref, lookup
      return <Input id={id} type="text" disabled={disabled} value={stringValue} onChange={(e) => onChange(field.fieldKey, e.target.value)} />
  }
}

/**
 * Toggleable chip picker for which of a document type's schema fields OCR
 * should attempt to extract (the `requestedFields` sent alongside the
 * upload -- see `lib/documents/api.ts#uploadDocument`). A mandatory field
 * is always selected and can't be turned off -- attempting a real backend
 * upload without one would just fail `MANDATORY_FIELD_NOT_REQUESTED`
 * validation anyway, so the chip disables that interaction up front
 * instead of letting the user hit an error. Selection state itself lives
 * in the parent (`DocumentUploadForm`); this component is presentational
 * plus its own chip-level ARIA wiring.
 */
export function FieldChips({
  schema,
  loading,
  selected,
  onToggle,
  onSelectAll,
  onClear,
  disabled,
}: {
  schema: DocumentTypeSchemaResponseDto | undefined
  loading: boolean
  selected: Set<string>
  onToggle: (fieldKey: string) => void
  onSelectAll: () => void
  onClear: () => void
  disabled?: boolean
}) {
  if (loading) {
    return <p className="text-[12.5px] text-muted-foreground">Loading fields…</p>
  }

  const visibleFields = visibleFieldsOf(schema)
  if (visibleFields.length === 0) {
    return null
  }

  return (
    <div className="mb-5">
      <div className="mb-2.5 flex items-center justify-between gap-3">
        <p className="text-[12.5px] font-semibold text-foreground">Fields for this document type</p>
        <div className="flex items-center gap-2.5">
          <button
            type="button"
            disabled={disabled}
            onClick={onSelectAll}
            className="text-[11.5px] font-semibold text-primary hover:underline disabled:cursor-not-allowed disabled:opacity-60"
          >
            Select all
          </button>
          <button
            type="button"
            disabled={disabled}
            onClick={onClear}
            className="text-[11.5px] font-semibold text-primary hover:underline disabled:cursor-not-allowed disabled:opacity-60"
          >
            Clear
          </button>
        </div>
      </div>
      <div className="flex flex-wrap gap-2">
        {visibleFields.map((field) => {
          const isSelected = field.mandatory || selected.has(field.fieldKey)
          const chipClassName = isSelected
            ? "border-primary bg-primary text-primary-foreground"
            : "border-[var(--border-strong)] bg-transparent text-foreground hover:bg-muted"
          return (
            <button
              key={field.fieldId}
              type="button"
              role="button"
              aria-pressed={isSelected}
              disabled={disabled || field.mandatory}
              onClick={() => onToggle(field.fieldKey)}
              className={`inline-flex items-center gap-1.5 rounded-full border px-3 py-1 text-[12px] font-medium transition-colors disabled:cursor-not-allowed ${chipClassName} ${field.mandatory ? "disabled:opacity-100" : "disabled:opacity-60"}`}
            >
              {field.label}
              {field.mandatory && (
                <Tooltip>
                  <TooltipTrigger render={(props) => <span {...props}><Lock size={11} className="shrink-0" /></span>} />
                  <TooltipContent>
                    {`${field.label} is required for this document type and can't be deselected.`}
                  </TooltipContent>
                </Tooltip>
              )}
              {field.ocrExtractable && !field.mandatory && (
                // The same hint as the label badge further down the form:
                // this chip is the FIRST "OCR" a user meets, so leaving it
                // unexplained is the one place the tag has to carry its
                // own meaning.
                <Tooltip>
                  <TooltipTrigger
                    render={(props) => (
                      <span
                        {...props}
                        className={
                          isSelected
                            ? "rounded-full border border-primary-foreground/40 px-1.5 py-0.5 text-[10px] font-normal normal-case"
                            : "rounded-full border border-border px-1.5 py-0.5 text-[10px] font-normal normal-case text-muted-foreground"
                        }
                      >
                        OCR
                      </span>
                    )}
                  />
                  <TooltipContent>{getTooltip("documents.ocr.badge")}</TooltipContent>
                </Tooltip>
              )}
            </button>
          )
        })}
      </div>
      <p className="mt-2 text-[12px] text-muted-foreground">
        Select the fields you want OCR to extract. Anything it can&apos;t find stays editable below.
      </p>
    </div>
  )
}

/** A mandatory, visible field with no value yet -- used to gate the Upload/Save actions. */
export function hasMissingMandatoryField(schema: DocumentTypeSchemaResponseDto | undefined, values: MetadataValues): boolean {
  if (!schema) return false
  return schema.fields.some((f) => {
    if (f.hidden || !f.mandatory) return false
    const value = values[f.fieldKey]
    return value === undefined || value === "" || (Array.isArray(value) && value.length === 0)
  })
}
