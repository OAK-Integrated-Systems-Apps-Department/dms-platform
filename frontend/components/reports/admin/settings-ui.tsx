"use client"

import type { ReactNode } from "react"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { LoadFailed } from "@/components/ui/load-failed"
import { LoadingState } from "@/components/ui/loading-state"
import { Switch } from "@/components/ui/switch"
import { cn } from "@/lib/utils"

import type { Change, useSettingsTab } from "./use-settings-tab"

export type SettingsTab = ReturnType<typeof useSettingsTab>

/** What each setting is called in the "what changed" list. */
const LABELS: Record<string, string> = {
  enabled: "Reports", schedulingEnabled: "Scheduling", defaultTimeZone: "Default time zone", defaultLocale: "Locale",
  dateFormat: "Date format", numberFormat: "Number format", includeInactiveByDefault: "Include archived by default",
  allowedFormats: "File formats", defaultFormat: "Default format", csvDelimiter: "CSV delimiter", csvIncludeBom: "CSV byte-order mark",
  headerText: "Header", footerText: "Footer", confidentialityNotice: "Confidentiality notice", showLogo: "Logo",
  showFilterSummary: "Filter summary", showGeneratedBy: "Generated-by line", pdfPageSize: "PDF page size", pdfOrientation: "PDF orientation",
  maxRows: "Most rows", maxPdfRows: "Most PDF rows", maxConcurrentRuns: "Reports at once", fileRetentionDays: "Days files are kept",
  maxTemplatesPerUser: "Saved reports per person", maxSchedulesPerUser: "Schedules per person", maxSchedulesPerTenant: "Schedules in total",
  maxRecipientsPerSchedule: "Recipients per schedule", allowedDeliveryChannels: "Delivery channels", deliveryModeDefault: "Delivery",
  allowAttachments: "Attachments", maxAttachmentMb: "Largest attachment (MB)", allowExternalRecipients: "External recipients",
  externalDomainAllowlist: "Allowed domains", minScheduleInterval: "Shortest interval", pauseAfterConsecutiveFailures: "Pause after failures",
  catchUpWindowHours: "Catch-up window (hours)", failureNotifyAdmins: "Tell administrators about failures",
  failureAdminUserIds: "Failure alerts go to", skipEmptyScheduledReports: "Skip empty scheduled reports",
}

export const labelOf = (key: string) => LABELS[key] ?? key

export function show(value: unknown): string {
  if (value === null || value === undefined || value === "") return "platform value"
  if (typeof value === "boolean") return value ? "on" : "off"
  if (Array.isArray(value)) return value.length === 0 ? "none" : value.join(", ")
  return String(value)
}

/** One titled group of related settings. */
export function Section({ title, hint, children }: { title: string; hint?: string; children: ReactNode }) {
  return (
    <section className="rounded-lg border border-border bg-card">
      <header className="border-b border-border px-5 py-3.5">
        <h2 className="text-[14.5px] font-semibold">{title}</h2>
        {hint && <p className="mt-0.5 text-[12.5px] text-muted-foreground">{hint}</p>}
      </header>
      <div className="divide-y divide-border">{children}</div>
    </section>
  )
}

/** One setting: its name and an explanation on the left, its control on the right, and a server problem under it. */
export function Row({
  title, description, problem, children,
}: { title: string; description?: ReactNode; problem?: string; children: ReactNode }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-x-6 gap-y-3 px-5 py-4">
      <div className="min-w-[220px] flex-1">
        <h3 className="text-[13.5px] font-medium">{title}</h3>
        {description && <p className="mt-0.5 max-w-[560px] text-[12.5px] leading-[1.55] text-muted-foreground">{description}</p>}
        {problem && <p role="alert" className="mt-1 text-[12.5px] font-medium text-destructive">{problem}</p>}
      </div>
      <div className="max-w-full">{children}</div>
    </div>
  )
}

export function ToggleRow({
  title, description, checked, onChange, disabled, problem,
}: { title: string; description?: ReactNode; checked: boolean; onChange: (v: boolean) => void; disabled: boolean; problem?: string }) {
  return (
    <Row title={title} description={description} problem={problem}>
      <label className="flex items-center gap-2.5 text-[13px] font-medium">
        <Switch checked={checked} onCheckedChange={onChange} disabled={disabled} aria-label={title} />
        <span className="w-[28px]">{checked ? "On" : "Off"}</span>
      </label>
    </Row>
  )
}

export function TextRow({
  title, description, value, onChange, disabled, problem, multiline, maxLength, placeholder, width = "w-[260px]",
}: {
  title: string; description?: ReactNode; value: string; onChange: (v: string) => void; disabled: boolean; problem?: string
  multiline?: boolean; maxLength?: number; placeholder?: string; width?: string
}) {
  const classes = cn(width, "max-w-full")
  return (
    <Row title={title} description={description} problem={problem}>
      {multiline ? (
        <textarea
          aria-label={title}
          className={cn(classes, "min-h-[84px] rounded-md border border-input bg-transparent px-3 py-2 text-[13px] outline-none focus-visible:ring-3 focus-visible:ring-ring/50")}
          value={value}
          maxLength={maxLength}
          placeholder={placeholder}
          disabled={disabled}
          onChange={(e) => onChange(e.target.value)}
        />
      ) : (
        <Input aria-label={title} className={classes} value={value} maxLength={maxLength} placeholder={placeholder} disabled={disabled}
          onChange={(e) => onChange(e.target.value)} />
      )}
    </Row>
  )
}

/** A whole-number setting with the platform ceiling beside it - the organisation can go lower, never higher. */
export function NumberRow({
  title, description, value, onChange, disabled, problem, ceiling, min = 1,
}: {
  title: string; description?: ReactNode; value: unknown; onChange: (v: number | null) => void; disabled: boolean
  problem?: string; ceiling?: number | boolean; min?: number
}) {
  const max = typeof ceiling === "number" ? ceiling : undefined
  return (
    <Row title={title} description={description} problem={problem}>
      <div className="flex items-center gap-3">
        <Input
          aria-label={title}
          type="number"
          className="w-[130px]"
          min={min}
          max={max}
          value={value === null || value === undefined ? "" : String(value)}
          disabled={disabled}
          onChange={(e) => onChange(e.target.value === "" ? null : Number(e.target.value))}
        />
        {max !== undefined && <span className="text-[12px] text-muted-foreground">Platform ceiling: {max.toLocaleString()}</span>}
      </div>
    </Row>
  )
}

/** A choice of one value from a short list. */
export function ChoiceRow({
  title, description, value, options, onChange, disabled, problem,
}: {
  title: string; description?: ReactNode; value: string; options: { value: string; label: string }[]
  onChange: (v: string) => void; disabled: boolean; problem?: string
}) {
  return (
    <Row title={title} description={description} problem={problem}>
      <select
        aria-label={title}
        className="h-9 min-w-[180px] rounded-md border border-input bg-transparent px-2.5 text-[13px]"
        value={value}
        disabled={disabled}
        onChange={(e) => onChange(e.target.value)}
      >
        {options.map((o) => (
          <option key={o.value} value={o.value}>{o.label}</option>
        ))}
      </select>
    </Row>
  )
}

/** Any number of values from a short list, as check boxes. */
export function MultiChoiceRow({
  title, description, value, options, onChange, disabled, problem,
}: {
  title: string; description?: ReactNode; value: string[]; options: { value: string; label: string }[]
  onChange: (v: string[]) => void; disabled: boolean; problem?: string
}) {
  return (
    <Row title={title} description={description} problem={problem}>
      <div role="group" aria-label={title} className="flex flex-wrap gap-4">
        {options.map((o) => (
          <label key={o.value} className="flex items-center gap-2 text-[13px]">
            <input
              type="checkbox"
              checked={value.includes(o.value)}
              disabled={disabled}
              onChange={(e) => onChange(e.target.checked ? [...value, o.value] : value.filter((v) => v !== o.value))}
            />
            {o.label}
          </label>
        ))}
      </div>
    </Row>
  )
}

/** What differs from what is saved, as "Setting: old → new". */
export function ChangeList({ changes, heading }: { changes: Change[]; heading: string }) {
  if (changes.length === 0) return null
  return (
    <div className="rounded-lg border border-border bg-muted/40 px-5 py-3 text-[13px]">
      <p className="mb-1 font-medium">{heading}</p>
      <ul className="space-y-0.5">
        {changes.map((c) => (
          <li key={c.key}>
            {labelOf(c.key)}: <span className="text-muted-foreground">{show(c.from)}</span> → <span className="font-medium">{show(c.to)}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}

/** Save and Discard for one tab, with the unsaved and just-saved summaries above them. */
export function SaveBar({ tab, canEdit }: { tab: SettingsTab; canEdit: boolean }) {
  if (!canEdit) {
    return <p className="text-[12.5px] text-muted-foreground">You can look at these settings but not change them.</p>
  }
  const dirty = tab.changes.length > 0
  return (
    <div className="flex flex-col gap-3">
      <ChangeList changes={tab.changes} heading="Not saved yet" />
      <ChangeList changes={tab.saved} heading="Saved" />
      <div className="flex items-center gap-3">
        <Button disabled={!dirty || tab.saving} onClick={() => void tab.save()}>
          {tab.saving ? "Saving…" : "Save"}
        </Button>
        <Button variant="outline" disabled={!dirty || tab.saving} onClick={tab.discard}>
          Discard
        </Button>
      </div>
    </div>
  )
}

/** Loads the settings, shows the tab's rows once they are in, and the save bar under them. */
export function SettingsShell({ tab, canEdit, children }: { tab: SettingsTab; canEdit: boolean; children: ReactNode }) {
  if (tab.query.isPending) return <LoadingState message="Loading report settings…" />
  if (tab.query.isError || !tab.ready) {
    return <LoadFailed error={tab.query.error} fallback="Could not load the report settings." onRetry={() => void tab.query.refetch()} />
  }
  return (
    <div className="flex w-full flex-col gap-4">
      {children}
      <SaveBar tab={tab} canEdit={canEdit} />
    </div>
  )
}
