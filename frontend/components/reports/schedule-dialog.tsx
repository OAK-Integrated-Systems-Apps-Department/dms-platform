"use client"

import { useState } from "react"

import { RecipientPicker } from "@/components/reports/recipient-picker"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { SegmentedControl } from "@/components/ui/segmented-control"
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from "@/components/ui/sheet"
import type { CatalogDto, Frequency, Recipient, ScheduleDto, ScheduleRequest } from "@/lib/reports/api"
import { FORMAT_LABELS, WEEKDAYS } from "@/lib/reports/format"
import { fieldProblems } from "@/lib/reports/messages"
import { useCreateSchedule, useUpdateSchedule } from "@/lib/reports/use-reports"

interface ScheduleDialogProps {
  catalog: CatalogDto
  /** The report to schedule (when creating). */
  template: { id: string; name: string }
  /** Set when editing an existing schedule. */
  schedule?: ScheduleDto
  onClose: () => void
}

const CHANNEL_LABELS: Record<string, string> = { in_app: "In the app (notification)", email: "Email" }

function browserZone(fallback: string): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || fallback
  } catch {
    return fallback
  }
}

function zones(): string[] {
  try {
    return (Intl as unknown as { supportedValuesOf: (k: string) => string[] }).supportedValuesOf("timeZone")
  } catch {
    return []
  }
}

/**
 * Send a report on a timetable. It runs as its owner, so it always includes what the owner may see; each internal recipient gets a
 * file made with their own access. The organisation's limits (channels, attachments, external addresses) decide what is offered.
 */
export function ScheduleDialog({ catalog, template, schedule, onClose }: ScheduleDialogProps) {
  const settings = catalog.settings
  const create = useCreateSchedule()
  const update = useUpdateSchedule()
  const pending = create.isPending || update.isPending

  const [name, setName] = useState(schedule?.name ?? `${template.name} (scheduled)`)
  const [frequency, setFrequency] = useState<Frequency>(schedule?.frequency ?? "weekly")
  const [days, setDays] = useState<number[]>(schedule?.daysOfWeek ?? [1])
  const [dayOfMonth, setDayOfMonth] = useState<number>(schedule?.dayOfMonth ?? 1)
  const [time, setTime] = useState((schedule?.timeOfDay ?? "08:00").slice(0, 5))
  const [timeZone, setTimeZone] = useState(schedule?.timeZone ?? browserZone(settings.defaultTimeZone))
  const [startDate, setStartDate] = useState(schedule?.startDate ?? "")
  const [endDate, setEndDate] = useState(schedule?.endDate ?? "")
  const [format, setFormat] = useState(schedule?.format ?? (settings.allowedFormats.includes(settings.defaultFormat) ? settings.defaultFormat : settings.allowedFormats[0]))
  const [channels, setChannels] = useState<string[]>(schedule?.deliveryChannels ?? settings.allowedDeliveryChannels.slice(0, 1))
  const [mode, setMode] = useState(schedule?.deliveryMode ?? settings.deliveryModeDefault)
  const [recipients, setRecipients] = useState<Recipient[]>(schedule?.recipients ?? [])
  const [problems, setProblems] = useState<Record<string, string>>({})

  const emailOn = channels.includes("email")
  const mayAttach = settings.allowAttachments && emailOn
  const mayExternal = settings.allowExternalRecipients && catalog.capabilities.configure

  function validate(): Record<string, string> {
    const p: Record<string, string> = {}
    if (!name.trim()) p.name = "Give the schedule a name."
    if (frequency === "weekly" && days.length === 0) p.daysOfWeek = "Choose at least one day."
    if (!/^\d{2}:\d{2}$/.test(time)) p.timeOfDay = "Choose a time."
    if (!timeZone.trim()) p.timeZone = "Choose a time zone."
    if (startDate && endDate && endDate < startDate) p.endDate = "The end date must be on or after the start date."
    if (channels.length === 0) p.deliveryChannels = "Choose at least one way to deliver it."
    if (!format) p.format = "Choose a file format."
    return p
  }

  async function save() {
    const found = validate()
    setProblems(found)
    if (Object.keys(found).length) return
    const request: ScheduleRequest = {
      templateId: schedule?.templateId ?? template.id,
      name: name.trim(),
      frequency,
      daysOfWeek: frequency === "weekly" ? days : undefined,
      dayOfMonth: frequency === "monthly" ? dayOfMonth : null,
      timeOfDay: time,
      timeZone: timeZone.trim(),
      startDate: startDate || null,
      endDate: endDate || null,
      format,
      deliveryChannels: channels,
      deliveryMode: mayAttach ? mode : "link",
      recipients,
    }
    try {
      if (schedule) await update.mutateAsync({ id: schedule.id, request })
      else await create.mutateAsync(request)
      onClose()
    } catch (error) {
      setProblems(fieldProblems(error))
    }
  }

  const err = (key: string) =>
    problems[key] ? (
      <p role="alert" className="mt-1 text-[12.5px] text-destructive">
        {problems[key]}
      </p>
    ) : null

  return (
    <Sheet open onOpenChange={(open) => !open && !pending && onClose()}>
      <SheetContent side="right" className="w-full gap-0 data-[side=right]:sm:max-w-[620px]">
        <SheetHeader className="border-b border-border">
          <SheetTitle>{schedule ? "Edit schedule" : "Schedule report"}</SheetTitle>
          <SheetDescription>{template.name}</SheetDescription>
        </SheetHeader>
        <div className="flex-1 space-y-5 overflow-y-auto p-5">
          <div>
            <label htmlFor="sched-name" className="mb-1 block text-[13px] font-medium">
              Name
            </label>
            <Input id="sched-name" value={name} onChange={(e) => setName(e.target.value)} />
            {err("name")}
          </div>

          <div>
            <p className="mb-1 text-[13px] font-medium">How often</p>
            <SegmentedControl
              aria-label="How often"
              value={frequency}
              onChange={setFrequency}
              options={[
                ...(settings.minScheduleInterval === "hourly" || frequency === "hourly" ? ([{ value: "hourly", label: "Hourly" }] as const) : []),
                { value: "daily", label: "Daily" },
                { value: "weekly", label: "Weekly" },
                { value: "monthly", label: "Monthly" },
              ]}
            />
            {frequency === "hourly" && (
              <p className="mt-2 text-[12px] text-muted-foreground">Runs every hour, at the minute of the time chosen below.</p>
            )}
            {frequency === "weekly" && (
              <div className="mt-3 flex flex-wrap gap-2" role="group" aria-label="Days of the week">
                {WEEKDAYS.map((d) => (
                  <label key={d.value} className="flex items-center gap-1 text-[13px]">
                    <input
                      type="checkbox"
                      checked={days.includes(d.value)}
                      onChange={() => setDays(days.includes(d.value) ? days.filter((x) => x !== d.value) : [...days, d.value])}
                    />
                    {d.label}
                  </label>
                ))}
              </div>
            )}
            {err("daysOfWeek")}
            {frequency === "monthly" && (
              <div className="mt-3">
                <label htmlFor="sched-dom" className="mb-1 block text-[12.5px] text-muted-foreground">
                  Day of the month
                </label>
                <select
                  id="sched-dom"
                  value={dayOfMonth}
                  onChange={(e) => setDayOfMonth(Number(e.target.value))}
                  className="h-10 rounded-btn border border-border bg-background px-2 text-[13px]"
                >
                  {Array.from({ length: 28 }, (_, i) => i + 1).map((d) => (
                    <option key={d} value={d}>
                      {d}
                    </option>
                  ))}
                  <option value={-1}>Last day of the month</option>
                </select>
                {err("dayOfMonth")}
              </div>
            )}
          </div>

          <div className="grid gap-4 sm:grid-cols-2">
            <div>
              <label htmlFor="sched-time" className="mb-1 block text-[13px] font-medium">
                Time
              </label>
              <Input id="sched-time" type="time" value={time} onChange={(e) => setTime(e.target.value)} />
              {err("timeOfDay")}
            </div>
            <div>
              <label htmlFor="sched-zone" className="mb-1 block text-[13px] font-medium">
                Time zone
              </label>
              <Input id="sched-zone" list="sched-zones" value={timeZone} onChange={(e) => setTimeZone(e.target.value)} />
              <datalist id="sched-zones">
                {zones().map((z) => (
                  <option key={z} value={z} />
                ))}
              </datalist>
              {err("timeZone")}
            </div>
            <div>
              <label htmlFor="sched-start" className="mb-1 block text-[13px] font-medium">
                Start date <span className="font-normal text-muted-foreground">(optional)</span>
              </label>
              <Input id="sched-start" type="date" value={startDate} onChange={(e) => setStartDate(e.target.value)} />
              {err("startDate")}
            </div>
            <div>
              <label htmlFor="sched-end" className="mb-1 block text-[13px] font-medium">
                End date <span className="font-normal text-muted-foreground">(optional)</span>
              </label>
              <Input id="sched-end" type="date" value={endDate} onChange={(e) => setEndDate(e.target.value)} />
              {err("endDate")}
            </div>
          </div>

          <fieldset>
            <legend className="mb-1.5 text-[13px] font-medium">File format</legend>
            <div className="flex gap-3">
              {settings.allowedFormats.map((f) => (
                <label key={f} className="flex items-center gap-1.5 text-[13px]">
                  <input type="radio" name="sched-format" checked={format === f} onChange={() => setFormat(f)} />
                  {FORMAT_LABELS[f] ?? f.toUpperCase()}
                </label>
              ))}
            </div>
            {err("format")}
          </fieldset>

          <fieldset>
            <legend className="mb-1.5 text-[13px] font-medium">Deliver by</legend>
            <div className="flex flex-wrap gap-4">
              {settings.allowedDeliveryChannels.map((c) => (
                <label key={c} className="flex items-center gap-1.5 text-[13px]">
                  <input
                    type="checkbox"
                    checked={channels.includes(c)}
                    onChange={() => setChannels(channels.includes(c) ? channels.filter((x) => x !== c) : [...channels, c])}
                  />
                  {CHANNEL_LABELS[c] ?? c}
                </label>
              ))}
            </div>
            {err("deliveryChannels")}
            {mayAttach && (
              <div className="mt-3" role="radiogroup" aria-label="Email contents">
                <label className="mr-4 inline-flex items-center gap-1.5 text-[13px]">
                  <input type="radio" name="sched-mode" checked={mode === "link"} onChange={() => setMode("link")} />
                  Link to the file
                </label>
                <label className="inline-flex items-center gap-1.5 text-[13px]">
                  <input type="radio" name="sched-mode" checked={mode === "attachment"} onChange={() => setMode("attachment")} />
                  Attach the file
                </label>
                <p className="mt-1 text-[12px] text-muted-foreground">
                  Attachments up to {settings.maxAttachmentMb} MB; a larger file is sent as a link instead.
                </p>
                {err("deliveryMode")}
              </div>
            )}
          </fieldset>

          <div>
            <p className="mb-1.5 text-[13px] font-medium">Recipients</p>
            <RecipientPicker
              value={recipients}
              onChange={setRecipients}
              allowExternal={mayExternal}
              max={settings.maxRecipientsPerSchedule}
              error={problems.recipients}
            />
            <p className="mt-2 text-[12px] text-muted-foreground">With no recipients, the file is made for you.</p>
          </div>

          <div className="rounded-md border border-border bg-muted/40 p-3 text-[12.5px] leading-[1.55] text-muted-foreground">
            <p className="font-medium text-foreground">If a scheduled run fails</p>
            <p>
              It is tried again at the next time, and you are told. {settings.pauseAfterConsecutiveFailures
                ? `After ${settings.pauseAfterConsecutiveFailures} failures in a row`
                : "After several failures in a row"} your organisation&apos;s rules pause
              the schedule, and you are told that too. A paused schedule keeps its history and can be resumed.
            </p>
          </div>
        </div>
        <div className="flex justify-end gap-2 border-t border-border p-4">
          <Button type="button" variant="outline" onClick={onClose} disabled={pending}>
            Cancel
          </Button>
          <Button type="button" onClick={() => void save()} disabled={pending}>
            {pending ? "Saving…" : "Save schedule"}
          </Button>
        </div>
      </SheetContent>
    </Sheet>
  )
}
