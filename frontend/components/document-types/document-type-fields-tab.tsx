"use client"

import { useState } from "react"
import { ArrowDown, ArrowUp, ChevronDown, ChevronRight, Plus, Trash2 } from "lucide-react"
import { toast } from "sonner"

import { LabelHintsInput } from "@/components/metadata-fields/label-hints-input"
import { Checkbox } from "@/components/ui/checkbox"
import { Input } from "@/components/ui/input"
import { normalizeLabelHints } from "@/lib/metadata-fields/label-hints"
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
  /** The real type name (e.g. "Purchase Order") — the type-scoped hint editor's helper text names it so it's unmistakable which scope is being edited. */
  documentTypeName: string
  currentFields: DocumentTypeFieldResponseDto[]
  availableFields: MetadataFieldResponseDto[]
  onPublish: (rows: Row[]) => Promise<string | null>
  /** Saves one field's type-scoped OCR label hints immediately — separate from `onPublish`, and never part of its payload. */
  onSaveLabelHints: (fieldId: string, labelAliases: string[]) => Promise<string | null>
  canUpdate: boolean
}

/** One row's unsaved "Hints for this type" work — see `hintDrafts` in the tab. */
interface HintDraft {
  /** The chip list being edited, in priority order. */
  hints: string[]
  /** Text still in the input — refused on blur (too short, no letter/digit…), or not yet committed. */
  pendingText: string
}

function sameHints(a: readonly string[], b: readonly string[]): boolean {
  return a.length === b.length && a.every((hint, i) => hint === b[i])
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
 *
 * Each already-published row also has an expandable "Hints for this type"
 * editor: OCR label hints that apply only to documents of THIS type, saved
 * straight away through their own endpoint (no new schema version, not part
 * of the Publish payload). They live in their own table rather than on the
 * versioned mapping rows, which is also why the matrix page's bulk mapping
 * save can't wipe them.
 */
export function DocumentTypeFieldsTab({
  documentTypeName,
  currentFields,
  availableFields,
  onPublish,
  onSaveLabelHints,
  canUpdate,
}: DocumentTypeFieldsTabProps) {
  const [rows, setRows] = useState<Row[]>(() => toRows(currentFields))
  // Render-time state adjustment (React's documented pattern for "reset
  // state when a prop changes") instead of a useEffect + setState — avoids
  // the extra cascading render an effect-based sync would cause, and reacts
  // the moment `currentFields` changes (e.g. after a successful publish
  // refetches it) rather than one render later.
  //
  // Keyed on the mapping's own content (exactly what `toRows` reads), not
  // on the `currentFields` array reference: saving type-scoped hints
  // changes each response row's `labelAliases` without creating a new
  // schema version, and both that cache patch (see useDocumentType's
  // saveFieldLabelAliases) and any later window-focus refetch that only
  // differs in hints hand this component a NEW array. Resetting on the
  // reference would silently throw away mapping edits the user hasn't
  // published yet every time they saved a hint.
  const mappingSignature = JSON.stringify(toRows(currentFields))
  const [syncedSignature, setSyncedSignature] = useState(mappingSignature)
  if (mappingSignature !== syncedSignature) {
    setSyncedSignature(mappingSignature)
    setRows(toRows(currentFields))
  }

  const [addFieldId, setAddFieldId] = useState("")
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [expandedHints, setExpandedHints] = useState<Set<string>>(() => new Set())
  // Unsaved "Hints for this type" edits, keyed by fieldId — only rows whose
  // chips differ from what's saved or that still have text in the input.
  // Held here rather than inside each row's editor so the tab itself knows
  // which rows have unsaved hints: their toggles are marked (the panel may
  // be collapsed), and the Publish button warns that it doesn't save them —
  // hints have their own Save, deliberately separate from the mapping, and
  // Publish is the button someone finishing up on this tab reaches for.
  // A draft stays until it's saved or discarded; a refetch that changes the
  // saved list (someone else's edit) doesn't throw away what was typed here.
  const [hintDrafts, setHintDrafts] = useState<Record<string, HintDraft>>({})

  const mappedFieldIds = new Set(rows.map((r) => r.fieldId))
  const addableFields = availableFields.filter((f) => f.status === "active" && !mappedFieldIds.has(f.fieldId))

  // Type-scoped hints can only be saved for a field in the CURRENT published
  // version (dms-core 404s otherwise) — so a row only added locally, not yet
  // published, is absent here and its hint editor stays disabled.
  const publishedTypeHints = new Map(currentFields.map((f) => [f.fieldId, f.labelAliases ?? []]))
  const globalHintsByFieldId = new Map(availableFields.map((f) => [f.fieldId, f.labelAliases]))

  function hasUnsavedHints(fieldId: string): boolean {
    const saved = publishedTypeHints.get(fieldId)
    const draft = hintDrafts[fieldId]
    // A draft left on a field a Publish has since unmapped no longer counts: its editor is disabled and ignores it.
    return saved !== undefined && draft !== undefined && (!sameHints(draft.hints, saved) || draft.pendingText.trim() !== "")
  }
  const rowsWithUnsavedHints = rows.filter((row) => hasUnsavedHints(row.fieldId))

  function updateHintDraft(fieldId: string, saved: string[], patch: Partial<HintDraft>) {
    setHintDrafts((prev) => {
      const next = { ...(prev[fieldId] ?? { hints: saved, pendingText: "" }), ...patch }
      const rest = { ...prev }
      delete rest[fieldId]
      // Back to exactly the saved list with nothing half-typed: not a draft any more.
      return sameHints(next.hints, saved) && !next.pendingText.trim() ? rest : { ...rest, [fieldId]: next }
    })
  }

  function clearHintDraft(fieldId: string) {
    setHintDrafts((prev) => {
      if (!(fieldId in prev)) return prev
      const rest = { ...prev }
      delete rest[fieldId]
      return rest
    })
  }

  function toggleHints(fieldId: string) {
    setExpandedHints((prev) => {
      const next = new Set(prev)
      if (next.has(fieldId)) next.delete(fieldId)
      else next.add(fieldId)
      return next
    })
  }

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
          {rows.map((row, index) => {
            const savedTypeHints = publishedTypeHints.get(row.fieldId)
            const savedHintCount = savedTypeHints?.length ?? 0
            const hintsExpanded = expandedHints.has(row.fieldId)
            const hintsUnsaved = hasUnsavedHints(row.fieldId)
            const hintsPanelId = `type-hints-panel-${row.fieldId}`
            return (
              <div key={row.fieldId} className="rounded-btn border border-border bg-muted/40 px-3.5 py-3">
                <div className="flex flex-wrap items-center gap-3">
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
                    {/* The sr-only parts give every row's toggle a distinct accessible name ("Hints for this
                        type — PO Number, 2 hints, unsaved changes") — the visible text alone is identical on
                        every row, which is useless in a screen reader's list of buttons. */}
                    <button
                      type="button"
                      onClick={() => toggleHints(row.fieldId)}
                      aria-expanded={hintsExpanded}
                      aria-controls={hintsPanelId}
                      className="mt-1 flex items-center gap-1 text-[11.5px] font-semibold text-primary hover:underline"
                    >
                      {hintsExpanded ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
                      Hints for this type{" "}
                      <span className="sr-only">— {row.label}</span>
                      {savedHintCount > 0 && (
                        <>
                          <span aria-hidden="true" className="rounded-full border border-primary/40 px-1.5 text-[10px] font-semibold">
                            {savedHintCount}
                          </span>
                          <span className="sr-only">, {savedHintCount} {savedHintCount === 1 ? "hint" : "hints"}</span>
                        </>
                      )}
                      {hintsUnsaved && (
                        <>
                          <span aria-hidden="true" className="rounded-full border border-[var(--gold-foreground)] px-1.5 text-[10px] font-semibold text-[var(--gold-foreground)]">
                            Unsaved
                          </span>
                          <span className="sr-only">, unsaved changes</span>
                        </>
                      )}
                    </button>
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

                  {/* "…from this document type", not just "Remove <label>": the hint chips in this row's
                      expanded panel are named "Remove <hint>", and a hint is often the label itself. */}
                  {canUpdate && (
                    <button type="button" onClick={() => removeRow(row.fieldId)} aria-label={`Remove ${row.label} from this document type`} className="flex size-8 items-center justify-center rounded-sm text-[var(--rust)] hover:bg-[var(--rust-tint)]">
                      <Trash2 size={15} />
                    </button>
                  )}
                </div>

                {/* Hidden rather than unmounted when collapsed, so a refused entry still in the input
                    (and its reason) survives collapsing the row, along with the draft chips held above. */}
                <div id={hintsPanelId} hidden={!hintsExpanded}>
                  <TypeFieldHintsEditor
                    fieldId={row.fieldId}
                    fieldLabel={row.label}
                    documentTypeName={documentTypeName}
                    savedHints={savedTypeHints}
                    draft={hintDrafts[row.fieldId]}
                    globalHints={globalHintsByFieldId.get(row.fieldId)}
                    canUpdate={canUpdate}
                    onDraftChange={(patch) => updateHintDraft(row.fieldId, savedTypeHints ?? [], patch)}
                    onDraftClear={() => clearHintDraft(row.fieldId)}
                    onSave={(hints) => onSaveLabelHints(row.fieldId, hints)}
                  />
                </div>
              </div>
            )
          })}
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
        <div className="flex flex-col items-end gap-2">
          {/* Not a blocker: publishing the mapping without those hints is legitimate. It just must
              not look like it saved them — see `hintDrafts`. Stays up after a publish, since the
              drafts are still unsaved. */}
          {rowsWithUnsavedHints.length > 0 && (
            <p id="unsaved-type-hints-warning" className="max-w-[560px] text-right text-[12px] font-semibold text-[var(--gold-foreground)]">
              Unsaved hints for this type on {rowsWithUnsavedHints.map((row) => row.label).join(", ")}. Publishing
              doesn&apos;t save hints — use Save hints in {rowsWithUnsavedHints.length === 1 ? "that row" : "each of those rows"}.
            </p>
          )}
          <button
            type="button"
            onClick={handlePublish}
            disabled={submitting}
            aria-describedby={rowsWithUnsavedHints.length > 0 ? "unsaved-type-hints-warning" : undefined}
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

/**
 * One mapped field's "Hints for this type" editor — OCR label hints scoped
 * to this document type only (e.g. a bare "Number" for a Purchase Order's PO
 * number, which would be ambiguous on an Invoice), tried BEFORE the field's
 * global hints. The global ones are shown read-only underneath for context:
 * they're edited on the field itself (metadata-field-form.tsx), whose own
 * helper text points here in turn, so each surface says which scope it is.
 *
 * `savedHints` is `undefined` for a row that's only been added locally and
 * not published yet — there's nothing server-side to attach hints to until
 * it is, so the editor is shown disabled with a note rather than hidden
 * (hiding it would make the feature look missing for new fields).
 *
 * The chips being edited and any text left in the input are held by the tab
 * (`draft`, `undefined` while nothing differs from what's saved) — see
 * `hintDrafts` there for why.
 */
function TypeFieldHintsEditor({
  fieldId,
  fieldLabel,
  documentTypeName,
  savedHints,
  draft,
  globalHints,
  canUpdate,
  onDraftChange,
  onDraftClear,
  onSave,
}: {
  fieldId: string
  fieldLabel: string
  documentTypeName: string
  savedHints: string[] | undefined
  draft: HintDraft | undefined
  /** The field's global hints from the catalogue list — `undefined` when that list isn't available (still loading, or no permission to view fields). */
  globalHints: string[] | undefined
  canUpdate: boolean
  onDraftChange: (patch: Partial<HintDraft>) => void
  onDraftClear: () => void
  onSave: (hints: string[]) => Promise<string | null>
}) {
  const published = savedHints !== undefined
  const saved = savedHints ?? []
  // An unpublished row's input is disabled, so it can only have a draft left
  // over from before a Publish unmapped the field — nothing to show or save.
  const activeDraft = published ? draft : undefined
  const hints = activeDraft ? activeDraft.hints : saved
  // Text typed into the input but not yet turned into a chip. Tracked so
  // Save enables as soon as something's typed — the input commits it as a
  // chip on blur, which lands before the Save click does.
  const pendingText = activeDraft?.pendingText ?? ""

  const [saving, setSaving] = useState(false)
  // Bumped by Discard to remount the input, which also clears any half-typed text inside it.
  const [inputKey, setInputKey] = useState(0)

  const dirty = !sameHints(hints, saved)
  const editable = canUpdate && published
  const canSave = editable && !saving && (dirty || pendingText.trim() !== "")

  const inputId = `type-hints-input-${fieldId}`
  const helpId = `type-hints-help-${fieldId}`

  async function handleSave() {
    // Anything still in the input after its blur-commit was refused (too
    // short, no letters/digits…) and its reason is showing under the input —
    // saving now would quietly drop it, so leave it for the user to fix.
    if (pendingText.trim()) return
    const normalized = normalizeLabelHints(hints)
    setSaving(true)
    const message = await onSave(normalized)
    setSaving(false)
    if (message) {
      toast.error(message)
      return
    }
    // By now the saved list (`savedHints`) already holds what was just
    // stored — useDocumentType patches its cache before the save resolves —
    // so dropping the draft shows the saved chips, with no flash of the old ones.
    onDraftClear()
    toast.success(
      normalized.length > 0
        ? `Saved ${documentTypeName} hints for ${fieldLabel}.`
        : `Cleared ${documentTypeName} hints for ${fieldLabel}.`
    )
  }

  function handleDiscard() {
    onDraftClear()
    setInputKey((key) => key + 1)
  }

  const noHintsAnywhere = hints.length === 0 && globalHints !== undefined && globalHints.length === 0

  return (
    <div className="mt-3 rounded-btn border border-dashed border-[var(--border-strong)] bg-card px-3.5 py-3">
      <label htmlFor={inputId} className="mb-1.5 block text-[11px] font-semibold tracking-[0.05em] text-muted-foreground uppercase">
        Hints for this type{" "}
        <span className="sr-only">— {fieldLabel}</span>
      </label>
      <LabelHintsInput
        key={inputKey}
        id={inputId}
        value={hints}
        onChange={(next) => onDraftChange({ hints: next })}
        onInputChange={(text) => onDraftChange({ pendingText: text })}
        disabled={!editable || saving}
        placeholder="Type a hint, then press Enter or comma"
        aria-describedby={helpId}
      />
      <p id={helpId} className="mt-1.5 text-[12px] leading-[1.5] text-muted-foreground">
        Only for {documentTypeName} documents — tried before the field&apos;s global hints. Global hints are edited on
        the field itself.
      </p>
      {!published && (
        <p className="mt-1.5 text-[12px] font-semibold text-[var(--gold-foreground)]">
          Publish this field mapping first — hints for this type can only be added once the field is in the published version.
        </p>
      )}

      {editable && (
        <div className="mt-2.5 flex flex-wrap items-center gap-2">
          <button
            type="button"
            onClick={handleSave}
            disabled={!canSave}
            className="flex h-8 items-center rounded-btn border border-primary bg-primary px-3.5 text-[12.5px] font-semibold text-primary-foreground disabled:cursor-not-allowed disabled:opacity-60"
          >
            {saving ? "Saving…" : "Save hints"}
          </button>
          {(dirty || pendingText.trim() !== "") && !saving && (
            <button
              type="button"
              onClick={handleDiscard}
              className="flex h-8 items-center rounded-btn border border-[var(--border-strong)] bg-card px-3.5 text-[12.5px] font-semibold text-foreground"
            >
              Discard
            </button>
          )}
          <span className="text-[11.5px] text-muted-foreground">Saves straight away — no need to publish a new version.</span>
        </div>
      )}

      <div className="mt-3 border-t border-dashed border-border pt-2.5">
        <p className="mb-1.5 text-[11px] font-semibold tracking-[0.05em] text-muted-foreground uppercase">
          Global hints <span className="normal-case">(read-only, tried after these)</span>
        </p>
        {globalHints === undefined ? (
          <p className="text-[12px] text-muted-foreground">Global hints aren&apos;t available here.</p>
        ) : globalHints.length === 0 ? (
          <p className="text-[12px] text-muted-foreground">None.</p>
        ) : (
          <div className="flex flex-wrap gap-1.5">
            {globalHints.map((hint) => (
              <span
                key={hint}
                className="inline-flex items-center rounded-[6px] border border-[var(--border-strong)] bg-muted px-2.5 py-[3px] text-[12px] font-semibold text-muted-foreground"
              >
                {hint}
              </span>
            ))}
          </div>
        )}
        {noHintsAnywhere && (
          <p className="mt-1.5 text-[12px] text-muted-foreground">
            With no hints at all, OCR looks for the field&apos;s own label, &ldquo;{fieldLabel}&rdquo;.
          </p>
        )}
      </div>
    </div>
  )
}
