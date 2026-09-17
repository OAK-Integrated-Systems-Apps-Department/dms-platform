"use client"

import { useState } from "react"
import { ArrowDown, ArrowUp, Plus, Trash2 } from "lucide-react"

import { Checkbox } from "@/components/ui/checkbox"
import { Input } from "@/components/ui/input"
import { cn } from "@/lib/utils"

import type { DocumentTypeFieldResponseDto } from "@/lib/document-type-fields/api"
import type { MetadataFieldResponseDto } from "@/lib/metadata-fields/api"

interface Row {
  fieldId: string
  fieldKey: string
  label: string
  mandatory: boolean
  hidden: boolean
  displayOrder: number
  fieldGroup: string
  defaultValueOverride: string
  /** True once this field newly became mandatory on a prior publish and hasn't been backfilled — read-only display, not editable here. */
  requiresBackfill: boolean
}

interface DocumentTypeFieldsTabProps {
  currentFields: DocumentTypeFieldResponseDto[]
  availableFields: MetadataFieldResponseDto[]
  onPublish: (rows: Row[]) => Promise<string | null>
  canUpdate: boolean
}

function toRows(fields: DocumentTypeFieldResponseDto[]): Row[] {
  return [...fields]
    .sort((a, b) => a.displayOrder - b.displayOrder)
    .map((f) => ({
      fieldId: f.fieldId,
      fieldKey: f.fieldKey,
      label: f.label,
      mandatory: f.mandatory,
      hidden: f.hidden,
      displayOrder: f.displayOrder,
      fieldGroup: f.fieldGroup ?? "",
      defaultValueOverride: f.defaultValueOverride ?? "",
      requiresBackfill: f.requiresBackfill,
    }))
}

/**
 * Document Type detail page's "Fields" tab (A6) — editing this one type's
 * mapping: mandatory/hidden toggles, default override, and up/down
 * reordering (no drag-and-drop library exists in this frontend — see the
 * approved plan's call-out on that constraint).
 * "Publish" sends the whole row set to `PUT /document-types/{id}/fields`,
 * which the backend turns into a new schema version.
 */
export function DocumentTypeFieldsTab({ currentFields, availableFields, onPublish, canUpdate }: DocumentTypeFieldsTabProps) {
  const [rows, setRows] = useState<Row[]>(() => toRows(currentFields))
  // Render-time state adjustment (React's documented pattern for "reset
  // state when a prop changes") instead of a useEffect + setState — avoids
  // the extra cascading render an effect-based sync would cause, and reacts
  // the moment `currentFields` changes (e.g. after a successful publish
  // refetches it) rather than one render later.
  const [syncedFields, setSyncedFields] = useState(currentFields)
  if (currentFields !== syncedFields) {
    setSyncedFields(currentFields)
    setRows(toRows(currentFields))
  }

  const [addFieldId, setAddFieldId] = useState("")
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const mappedFieldIds = new Set(rows.map((r) => r.fieldId))
  const addableFields = availableFields.filter((f) => f.status === "active" && !mappedFieldIds.has(f.fieldId))

  function updateRow(fieldId: string, patch: Partial<Row>) {
    setRows((prev) => prev.map((r) => (r.fieldId === fieldId ? { ...r, ...patch } : r)))
  }

  function removeRow(fieldId: string) {
    setRows((prev) => prev.filter((r) => r.fieldId !== fieldId))
  }

  function move(fieldId: string, direction: -1 | 1) {
    setRows((prev) => {
      const index = prev.findIndex((r) => r.fieldId === fieldId)
      const target = index + direction
      if (index < 0 || target < 0 || target >= prev.length) return prev
      const next = [...prev]
      ;[next[index], next[target]] = [next[target], next[index]]
      return next.map((row, i) => ({ ...row, displayOrder: (i + 1) * 10 }))
    })
  }

  function handleAddField() {
    if (!addFieldId) return
    const field = availableFields.find((f) => f.fieldId === addFieldId)
    if (!field) return
    setRows((prev) => [
      ...prev,
      {
        fieldId: field.fieldId,
        fieldKey: field.fieldKey,
        label: field.label,
        mandatory: false,
        hidden: false,
        displayOrder: (prev.at(-1)?.displayOrder ?? 0) + 10,
        fieldGroup: "",
        defaultValueOverride: "",
        requiresBackfill: false,
      },
    ])
    setAddFieldId("")
  }

  async function handlePublish() {
    setSubmitting(true)
    setError(null)
    const message = await onPublish(rows)
    setSubmitting(false)
    if (message) setError(message)
  }

  return (
    <div className="rounded-lg border border-border bg-card px-[30px] py-[26px] shadow-[var(--ledger-shadow)]">
      {error && (
        <div className="mb-5 flex items-start gap-2 rounded-btn border border-[var(--rust)] bg-[var(--rust-tint)] px-3.5 py-2.5 text-[12.5px] text-[var(--rust)]">
          {error}
        </div>
      )}

      {rows.length === 0 ? (
        <p className="mb-4 rounded-btn border border-dashed border-[var(--border-strong)] bg-muted px-3.5 py-3 text-[12.5px] text-muted-foreground">
          No fields mapped yet — add fields from the catalogue below.
        </p>
      ) : (
        <div className="mb-4 flex flex-col gap-2">
          {rows.map((row, index) => (
            <div key={row.fieldId} className="flex flex-wrap items-center gap-3 rounded-btn border border-border bg-muted/40 px-3.5 py-3">
              <div className="flex flex-col">
                <button type="button" disabled={!canUpdate || index === 0} onClick={() => move(row.fieldId, -1)} aria-label={`Move ${row.label} up`} className="flex size-5 items-center justify-center text-muted-foreground hover:text-foreground disabled:opacity-30">
                  <ArrowUp size={13} />
                </button>
                <button type="button" disabled={!canUpdate || index === rows.length - 1} onClick={() => move(row.fieldId, 1)} aria-label={`Move ${row.label} down`} className="flex size-5 items-center justify-center text-muted-foreground hover:text-foreground disabled:opacity-30">
                  <ArrowDown size={13} />
                </button>
              </div>

              <div className="min-w-[160px] flex-1">
                <p className="text-[13.5px] font-semibold text-foreground">{row.label}</p>
                <p className="font-mono text-[11.5px] text-muted-foreground">{row.fieldKey}</p>
                {row.requiresBackfill && (
                  <p className="mt-0.5 text-[11px] font-semibold text-[var(--gold-foreground)]">Newly mandatory — flagged for backfill</p>
                )}
              </div>

              <Checkbox
                id={`mandatory-${row.fieldId}`}
                label="Mandatory"
                checked={row.mandatory}
                disabled={!canUpdate}
                onCheckedChange={(checked) => updateRow(row.fieldId, { mandatory: checked })}
              />
              <Checkbox
                id={`hidden-${row.fieldId}`}
                label="Hidden"
                checked={row.hidden}
                disabled={!canUpdate}
                onCheckedChange={(checked) => updateRow(row.fieldId, { hidden: checked })}
              />

              <div className="w-[180px]">
                <Input
                  placeholder="Default override"
                  value={row.defaultValueOverride}
                  disabled={!canUpdate}
                  onChange={(e) => updateRow(row.fieldId, { defaultValueOverride: e.target.value })}
                  className="h-9"
                />
              </div>

              {canUpdate && (
                <button type="button" onClick={() => removeRow(row.fieldId)} aria-label={`Remove ${row.label}`} className="flex size-8 items-center justify-center rounded-sm text-[var(--rust)] hover:bg-[var(--rust-tint)]">
                  <Trash2 size={15} />
                </button>
              )}
            </div>
          ))}
        </div>
      )}

      {canUpdate && (
        <div className="mb-5 flex flex-wrap items-end gap-2 rounded-btn border border-dashed border-[var(--border-strong)] bg-muted px-3 py-3">
          <div className="min-w-[220px] flex-1">
            <label className="mb-1 block text-[11px] font-semibold tracking-[0.05em] text-muted-foreground uppercase">Add field from catalogue</label>
            <select
              value={addFieldId}
              onChange={(e) => setAddFieldId(e.target.value)}
              className="h-9 w-full appearance-none rounded-btn border border-[var(--border-strong)] bg-card px-2.5 text-[13px] text-foreground outline-none"
            >
              <option value="">Select a field…</option>
              {addableFields.map((f) => (
                <option key={f.fieldId} value={f.fieldId}>{f.label} ({f.fieldKey})</option>
              ))}
            </select>
          </div>
          <button type="button" onClick={handleAddField} disabled={!addFieldId} className="flex h-9 items-center gap-1.5 rounded-btn border border-primary bg-primary px-3.5 text-[12.5px] font-semibold text-primary-foreground disabled:opacity-50">
            <Plus size={14} />
            Add Field
          </button>
        </div>
      )}

      {canUpdate && (
        <div className="flex justify-end">
          <button
            type="button"
            onClick={handlePublish}
            disabled={submitting}
            className={cn(
              "rounded-btn border border-primary bg-primary px-[22px] py-2.5 text-[13px] font-bold tracking-[0.02em] text-primary-foreground",
              "disabled:cursor-not-allowed disabled:opacity-60"
            )}
          >
            {submitting ? "Publishing…" : "Publish New Version"}
          </button>
        </div>
      )}
    </div>
  )
}
