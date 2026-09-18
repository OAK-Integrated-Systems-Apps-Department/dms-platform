"use client"

import { useState } from "react"
import { Check, Plus, Trash2 } from "lucide-react"

import { Input } from "@/components/ui/input"
import { SegmentedControl } from "@/components/ui/segmented-control"

import { VALIDATION_RULE_TYPES, type MetadataFieldValidationResponseDto, type ValidationRuleType } from "@/lib/metadata-fields/api"

const RULE_TYPE_LABELS: Record<ValidationRuleType, string> = {
  min_length: "Min length",
  max_length: "Max length",
  min_value: "Min value",
  max_value: "Max value",
  regex: "Regex",
  date_format: "Date format",
  date_range: "Date range",
  allowed_values: "Allowed values",
}

const RULE_VALUE_HINT: Record<ValidationRuleType, string> = {
  min_length: "e.g. 3",
  max_length: "e.g. 255",
  min_value: "e.g. 0",
  max_value: "e.g. 100",
  regex: "e.g. ^[A-Z]{2}\\d{4}$",
  date_format: "e.g. yyyy-MM-dd",
  date_range: "e.g. 2020-01-01/2030-12-31",
  allowed_values: "comma-separated, e.g. USD,EUR,GBP",
}

interface FieldValidationEditorProps {
  validations: MetadataFieldValidationResponseDto[]
  onAdd: (payload: { ruleType: ValidationRuleType; ruleValue: string; errorMessage: string; severity: "error" | "warning" }) => void
  onUpdate: (validationId: string, payload: { ruleValue: string; errorMessage: string; severity: "error" | "warning" }) => void
  onRemove: (validationId: string) => void
}

/**
 * Inline validation-rule editor for the Edit Field screen (A4). No
 * "required" rule type is offered — mandatory-ness always comes from
 * `document_type_fields.is_mandatory` on the mapping, set from the
 * Document Type Fields tab / Mapping matrix, never from a rule here.
 */
export function FieldValidationEditor({ validations, onAdd, onUpdate, onRemove }: FieldValidationEditorProps) {
  const [ruleType, setRuleType] = useState<ValidationRuleType>("min_length")
  const [ruleValue, setRuleValue] = useState("")
  const [errorMessage, setErrorMessage] = useState("")

  function handleAdd() {
    if (!ruleValue.trim()) return
    onAdd({ ruleType, ruleValue: ruleValue.trim(), errorMessage: errorMessage.trim(), severity: "error" })
    setRuleValue("")
    setErrorMessage("")
  }

  return (
    <div>
      {validations.length === 0 && (
        <p className="mb-3 rounded-btn border border-dashed border-[var(--border-strong)] bg-muted px-3.5 py-3 text-[12.5px] text-muted-foreground">
          No validation rules — this field only enforces mandatory-ness set on a document type&apos;s mapping.
        </p>
      )}

      <div className="mb-3 flex flex-col gap-2">
        {validations.map((rule) => (
          <ValidationRow
            key={rule.validationId}
            rule={rule}
            onUpdate={(payload) => onUpdate(rule.validationId, payload)}
            onRemove={() => onRemove(rule.validationId)}
          />
        ))}
      </div>

      <div className="flex flex-wrap items-end gap-2 rounded-btn border border-dashed border-[var(--border-strong)] bg-muted px-3 py-3">
        <div className="min-w-[150px]">
          <label className="mb-1 block text-[11px] font-semibold tracking-[0.05em] text-muted-foreground uppercase">Rule type</label>
          <select
            value={ruleType}
            onChange={(e) => setRuleType(e.target.value as ValidationRuleType)}
            className="h-9 w-full appearance-none rounded-btn border border-[var(--border-strong)] bg-card px-2.5 text-[13px] text-foreground outline-none"
          >
            {VALIDATION_RULE_TYPES.map((type) => (
              <option key={type} value={type}>{RULE_TYPE_LABELS[type]}</option>
            ))}
          </select>
        </div>
        <div className="min-w-[160px] flex-1">
          <label className="mb-1 block text-[11px] font-semibold tracking-[0.05em] text-muted-foreground uppercase">Rule value</label>
          <Input value={ruleValue} onChange={(e) => setRuleValue(e.target.value)} placeholder={RULE_VALUE_HINT[ruleType]} className="h-9" />
        </div>
        <div className="min-w-[160px] flex-1">
          <label className="mb-1 block text-[11px] font-semibold tracking-[0.05em] text-muted-foreground uppercase">
            Error message <span className="normal-case text-[var(--ink-faint)]">(optional)</span>
          </label>
          <Input value={errorMessage} onChange={(e) => setErrorMessage(e.target.value)} className="h-9" />
        </div>
        <button
          type="button"
          onClick={handleAdd}
          disabled={!ruleValue.trim()}
          className="flex h-9 items-center gap-1.5 rounded-btn border border-primary bg-primary px-3.5 text-[12.5px] font-semibold text-primary-foreground disabled:cursor-not-allowed disabled:opacity-60"
        >
          <Plus size={14} />
          Add Rule
        </button>
      </div>
    </div>
  )
}

function ValidationRow({
  rule,
  onUpdate,
  onRemove,
}: {
  rule: MetadataFieldValidationResponseDto
  onUpdate: (payload: { ruleValue: string; errorMessage: string; severity: "error" | "warning" }) => void
  onRemove: () => void
}) {
  const [ruleValue, setRuleValue] = useState(rule.ruleValue ?? "")
  const [errorMessage, setErrorMessage] = useState(rule.errorMessage ?? "")
  const dirty = ruleValue !== (rule.ruleValue ?? "") || errorMessage !== (rule.errorMessage ?? "")

  function save() {
    onUpdate({ ruleValue, errorMessage, severity: rule.severity })
  }

  function toggleSeverity(next: "error" | "warning") {
    onUpdate({ ruleValue, errorMessage, severity: next })
  }

  return (
    <div className="flex flex-wrap items-end gap-2 rounded-btn border border-border bg-card px-3 py-2.5">
      <span className="flex h-9 min-w-[130px] items-center text-[13px] font-semibold text-foreground">
        {RULE_TYPE_LABELS[rule.ruleType]}
      </span>
      <Input value={ruleValue} onChange={(e) => setRuleValue(e.target.value)} className="h-9 min-w-[160px] flex-1" />
      <Input value={errorMessage} onChange={(e) => setErrorMessage(e.target.value)} placeholder="Error message" className="h-9 min-w-[160px] flex-1" />
      <SegmentedControl
        value={rule.severity}
        onChange={toggleSeverity}
        options={[{ value: "error", label: "Error" }, { value: "warning", label: "Warning" }]}
      />
      {dirty && (
        <button type="button" onClick={save} aria-label="Save rule" className="flex size-9 items-center justify-center rounded-sm text-primary hover:bg-muted">
          <Check size={16} />
        </button>
      )}
      <button type="button" onClick={onRemove} aria-label="Delete rule" className="flex size-9 items-center justify-center rounded-sm text-[var(--rust)] hover:bg-[var(--rust-tint)]">
        <Trash2 size={15} />
      </button>
    </div>
  )
}
