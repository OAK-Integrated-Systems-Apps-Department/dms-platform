"use client"

import { useState } from "react"
import { Check, Plus, Trash2 } from "lucide-react"

import { Input } from "@/components/ui/input"
import { SegmentedControl } from "@/components/ui/segmented-control"
import { cn } from "@/lib/utils"

import type { MetadataFieldOptionResponseDto } from "@/lib/metadata-fields/api"

interface FieldOptionEditorProps {
  fieldId: string
  options: MetadataFieldOptionResponseDto[]
  onAdd: (payload: { optionValue: string; optionLabel: string; parentOptionId: string | null; displayOrder: number }) => void
  onUpdate: (
    optionId: string,
    payload: { optionValue: string; optionLabel: string; parentOptionId: string | null; displayOrder: number; status: "active" | "inactive" }
  ) => void
  onRemove: (optionId: string) => void
  /** True while an add-option mutation is in flight — disables the Add button so a rapid double-click can't fire two creates that compute the same displayOrder from the same stale list. */
  adding: boolean
}

/**
 * Inline dropdown/multi_select option list editor for the Edit Field
 * screen (A4) — value/label/display order/status per row, plus
 * `parentOptionId` for cascading lists (a plain select of the field's own
 * other options). A field with zero active options here is exactly what
 * triggers MDTM alternate flow 1 at validation time.
 */
export function FieldOptionEditor({ options, onAdd, onUpdate, onRemove, adding }: FieldOptionEditorProps) {
  const sorted = [...options].sort((a, b) => a.displayOrder - b.displayOrder)

  const [newValue, setNewValue] = useState("")
  const [newLabel, setNewLabel] = useState("")
  const [newParent, setNewParent] = useState("")

  function handleAdd() {
    if (!newValue.trim() || !newLabel.trim() || adding) return
    onAdd({
      optionValue: newValue.trim(),
      optionLabel: newLabel.trim(),
      parentOptionId: newParent || null,
      displayOrder: (sorted.at(-1)?.displayOrder ?? 0) + 1,
    })
    setNewValue("")
    setNewLabel("")
    setNewParent("")
  }

  return (
    <div>
      {sorted.length === 0 && (
        <p className="mb-3 rounded-btn border border-dashed border-[var(--border-strong)] bg-muted px-3.5 py-3 text-[12.5px] text-muted-foreground">
          No options yet — this field will block validation until at least one active option exists.
        </p>
      )}

      <div className="mb-3 flex flex-col gap-2">
        {sorted.map((option) => (
          <OptionRow
            key={option.optionId}
            option={option}
            allOptions={sorted}
            onUpdate={(payload) => onUpdate(option.optionId, payload)}
            onRemove={() => onRemove(option.optionId)}
          />
        ))}
      </div>

      <div className="flex flex-wrap items-end gap-2 rounded-btn border border-dashed border-[var(--border-strong)] bg-muted px-3 py-3">
        <div className="min-w-[140px] flex-1">
          <label className="mb-1 block text-[11px] font-semibold tracking-[0.05em] text-muted-foreground uppercase">Value</label>
          <Input value={newValue} onChange={(e) => setNewValue(e.target.value)} placeholder="e.g. confidential" className="h-9" />
        </div>
        <div className="min-w-[140px] flex-1">
          <label className="mb-1 block text-[11px] font-semibold tracking-[0.05em] text-muted-foreground uppercase">Label</label>
          <Input value={newLabel} onChange={(e) => setNewLabel(e.target.value)} placeholder="e.g. Confidential" className="h-9" />
        </div>
        <div className="min-w-[160px]">
          <label className="mb-1 block text-[11px] font-semibold tracking-[0.05em] text-muted-foreground uppercase">Parent option</label>
          <select
            value={newParent}
            onChange={(e) => setNewParent(e.target.value)}
            className="h-9 w-full appearance-none rounded-btn border border-[var(--border-strong)] bg-card px-2.5 text-[13px] text-foreground outline-none"
          >
            <option value="">None (top-level)</option>
            {sorted.map((o) => (
              <option key={o.optionId} value={o.optionId}>{o.optionLabel}</option>
            ))}
          </select>
        </div>
        <button
          type="button"
          onClick={handleAdd}
          disabled={adding || !newValue.trim() || !newLabel.trim()}
          className="flex h-9 items-center gap-1.5 rounded-btn border border-primary bg-primary px-3.5 text-[12.5px] font-semibold text-primary-foreground disabled:cursor-not-allowed disabled:opacity-60"
        >
          <Plus size={14} />
          Add Option
        </button>
      </div>
    </div>
  )
}

function OptionRow({
  option,
  allOptions,
  onUpdate,
  onRemove,
}: {
  option: MetadataFieldOptionResponseDto
  allOptions: MetadataFieldOptionResponseDto[]
  onUpdate: (payload: { optionValue: string; optionLabel: string; parentOptionId: string | null; displayOrder: number; status: "active" | "inactive" }) => void
  onRemove: () => void
}) {
  const [value, setValue] = useState(option.optionValue)
  const [label, setLabel] = useState(option.optionLabel)
  const [parentOptionId, setParentOptionId] = useState(option.parentOptionId ?? "")
  const dirty = value !== option.optionValue || label !== option.optionLabel || parentOptionId !== (option.parentOptionId ?? "")

  function save() {
    onUpdate({ optionValue: value, optionLabel: label, parentOptionId: parentOptionId || null, displayOrder: option.displayOrder, status: option.status })
  }

  function toggleStatus() {
    onUpdate({
      optionValue: value,
      optionLabel: label,
      parentOptionId: parentOptionId || null,
      displayOrder: option.displayOrder,
      status: option.status === "active" ? "inactive" : "active",
    })
  }

  return (
    <div className="flex flex-wrap items-end gap-2 rounded-btn border border-border bg-card px-3 py-2.5">
      <Input value={value} onChange={(e) => setValue(e.target.value)} className="h-9 min-w-[130px] flex-1" />
      <Input value={label} onChange={(e) => setLabel(e.target.value)} className="h-9 min-w-[130px] flex-1" />
      <select
        value={parentOptionId}
        onChange={(e) => setParentOptionId(e.target.value)}
        className="h-9 min-w-[150px] appearance-none rounded-btn border border-[var(--border-strong)] bg-muted px-2.5 text-[13px] text-foreground outline-none"
      >
        <option value="">None (top-level)</option>
        {allOptions.filter((o) => o.optionId !== option.optionId).map((o) => (
          <option key={o.optionId} value={o.optionId}>{o.optionLabel}</option>
        ))}
      </select>
      <SegmentedControl
        value={option.status}
        onChange={() => toggleStatus()}
        options={[{ value: "active", label: "Active" }, { value: "inactive", label: "Inactive" }]}
      />
      {dirty && (
        <button
          type="button"
          onClick={save}
          aria-label="Save option"
          className="flex size-9 items-center justify-center rounded-sm text-primary hover:bg-muted"
        >
          <Check size={16} />
        </button>
      )}
      <button
        type="button"
        onClick={onRemove}
        aria-label="Delete option"
        className={cn("flex size-9 items-center justify-center rounded-sm text-[var(--rust)] hover:bg-[var(--rust-tint)]")}
      >
        <Trash2 size={15} />
      </button>
    </div>
  )
}
