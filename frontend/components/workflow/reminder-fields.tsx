"use client"

import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { SegmentedControl } from "@/components/ui/segmented-control"

/**
 * The two kinds of reminder, in a few words - shown wherever they are configured (the template's defaults and a
 * stage's own settings) so nobody has to guess what "inactivity" means.
 */
export function ReminderHelp() {
  return (
    <div className="rounded-btn border border-border bg-muted px-3.5 py-2.5 text-[12.5px] leading-[1.6] text-muted-foreground">
      <p>
        <b className="font-semibold text-foreground">Due-date reminder</b> - sent a set number of hours <em>before</em> a task
        is due. It needs the stage to have SLA hours.
      </p>
      <p className="mt-1.5">
        <b className="font-semibold text-foreground">Inactivity reminder</b> - sent when nobody has claimed, delegated or
        answered anything on the task for a set number of hours. It works with or without an SLA.
      </p>
      <p className="mt-1.5">
        Escalation then happens at the due time, or - once the inactivity reminder has gone out - the set number of hours
        after it, whichever comes first. Each reminder, and the escalation, is sent once per task.
      </p>
    </div>
  )
}

/** A whole-hours input where an empty box means "not set" (null). */
export function HoursInput({
  id,
  value,
  onChange,
  invalid,
  disabled,
  placeholder,
  max = 8760,
}: {
  id: string
  value: number | null
  onChange: (value: number | null) => void
  invalid?: boolean
  disabled?: boolean
  placeholder?: string
  max?: number
}) {
  return (
    <Input
      id={id}
      type="number"
      min={1}
      max={max}
      inputMode="numeric"
      disabled={disabled}
      placeholder={placeholder}
      value={value ?? ""}
      aria-invalid={invalid}
      onChange={(e) => onChange(e.target.value === "" ? null : Number(e.target.value))}
    />
  )
}

type Mode = "inherit" | "own"

/**
 * A stage override with "Inherit from template" as the default: inheriting stores null (the backend's "use the
 * template's value"), "Set for this stage" reveals the hours. When inheriting, it shows what the template says.
 */
export function InheritableHours({
  id,
  label,
  value,
  inheritedValue,
  onChange,
  error,
  disabled,
  help,
  max,
}: {
  id: string
  label: string
  /** The stage's own value; null = inherit. */
  value: number | null
  /** The template's default, to show while inheriting; null = the template has none. */
  inheritedValue: number | null
  onChange: (value: number | null) => void
  error?: string
  disabled?: boolean
  help?: string
  max?: number
}) {
  const mode: Mode = value == null ? "inherit" : "own"
  return (
    <div>
      <Label htmlFor={id}>{label}</Label>
      <SegmentedControl<Mode>
        value={mode}
        disabled={disabled}
        onChange={(next) => {
          if (next === "inherit") onChange(null)
          else if (value == null) onChange(inheritedValue ?? 24)
        }}
        options={[
          { value: "inherit", label: "Inherit from template" },
          { value: "own", label: "Set for this stage" },
        ]}
      />
      {mode === "own" ? (
        <div className="mt-2.5 sm:w-[200px]">
          <HoursInput id={id} value={value} onChange={onChange} invalid={Boolean(error)} disabled={disabled} max={max} />
        </div>
      ) : (
        <p className="mt-2 text-[12.5px] text-muted-foreground">
          {inheritedValue != null
            ? `Uses the template's ${inheritedValue} hours.`
            : "The template has no default, so this is not used."}
        </p>
      )}
      {help && <p className="mt-1.5 text-[12px] text-muted-foreground">{help}</p>}
      {error && <p className="mt-1.5 text-[12px] text-destructive">{error}</p>}
    </div>
  )
}
