"use client"

import { ChevronDown, Info } from "lucide-react"

import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"

/** "How reminders work" - the longer explanation, kept out of the way in a tooltip (hover or keyboard focus). */
export function HowRemindersWork() {
  return (
    <Tooltip>
      <TooltipTrigger
        type="button"
        className="inline-flex items-center gap-1 rounded-btn text-[12px] font-semibold text-primary hover:underline"
      >
        <Info size={12} aria-hidden="true" />
        How reminders work
      </TooltipTrigger>
      <TooltipContent side="top" className="max-w-sm flex-col items-start gap-1.5 py-2 text-[12px] leading-[1.5]">
        <span>
          <b>Due-date reminder</b> - sent a set number of hours before a task is due. Needs the stage to have SLA hours.
        </span>
        <span>
          <b>Inactivity reminder</b> - sent when nobody has claimed, delegated or answered anything on the task for a
          set number of hours. Works with or without an SLA.
        </span>
        <span>
          Escalation happens at the due time or, once the inactivity reminder has gone out, the set number of hours
          after it - whichever comes first. Each reminder and the escalation is sent once per task.
        </span>
      </TooltipContent>
    </Tooltip>
  )
}

/**
 * A closed-by-default section with a one-line summary shown while closed. A native button toggles it, so it is
 * keyboard accessible without extra wiring.
 */
export function CollapsibleSection({
  title,
  summary,
  attention,
  open,
  onOpenChange,
  children,
}: {
  title: string
  summary: string
  /** A short marker (e.g. "Check") shown beside the summary while closed, so a problem is visible unopened. */
  attention?: string
  open: boolean
  onOpenChange: (open: boolean) => void
  children: React.ReactNode
}) {
  return (
    <div className="rounded-lg border border-dashed border-border bg-card">
      <button
        type="button"
        aria-expanded={open}
        onClick={() => onOpenChange(!open)}
        className="flex w-full items-center gap-3 px-4 py-2.5 text-left"
      >
        <span className="min-w-0 flex-1">
          <span className="block text-[12px] font-bold uppercase tracking-[0.08em] text-muted-foreground">{title}</span>
          {!open && (
            <span className="mt-0.5 flex items-center gap-2 text-[12.5px] text-muted-foreground">
              <span className="truncate">{summary}</span>
              {attention && (
                <span className="flex shrink-0 items-center gap-1 font-semibold text-[var(--gold-foreground)]">
                  <span className="size-2 rounded-full bg-[var(--gold)]" aria-hidden="true" />
                  {attention}
                </span>
              )}
            </span>
          )}
        </span>
        <ChevronDown
          size={15}
          aria-hidden="true"
          className={open ? "shrink-0 rotate-180 text-muted-foreground" : "shrink-0 text-muted-foreground"}
        />
      </button>
      {open && <div className="flex flex-col gap-4 border-t border-dashed border-border px-4 py-4">{children}</div>}
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

/**
 * A stage's inactivity field: one number input where empty means "inherit the template's value", which the
 * placeholder shows ("Template default: 72 h", or "Not set" when the template has none).
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
  /** The template's default; null = the template has none. */
  inheritedValue: number | null
  onChange: (value: number | null) => void
  error?: string
  disabled?: boolean
  help?: string
  max?: number
}) {
  return (
    <div className="sm:w-[300px]">
      <Label htmlFor={id}>{label}</Label>
      <HoursInput
        id={id}
        value={value}
        onChange={onChange}
        invalid={Boolean(error)}
        disabled={disabled}
        max={max}
        placeholder={inheritedValue != null ? `Template default: ${inheritedValue} h` : "Not set"}
      />
      {help && <p className="mt-1.5 text-[12px] text-muted-foreground">{help}</p>}
      {error && <p className="mt-1.5 text-[12px] text-destructive">{error}</p>}
    </div>
  )
}
