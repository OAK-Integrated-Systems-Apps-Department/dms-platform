/** A moment shown in a named time zone ("12 Mar 2026, 08:00"), falling back to the browser's zone if the name is not known. */
export function formatInZone(iso: string | null | undefined, timeZone?: string | null): string {
  if (!iso) return "—"
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return "—"
  try {
    return new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short", timeZone: timeZone ?? undefined }).format(date)
  } catch {
    return new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(date)
  }
}

export const WEEKDAYS = [
  { value: 1, label: "Mon" },
  { value: 2, label: "Tue" },
  { value: 3, label: "Wed" },
  { value: 4, label: "Thu" },
  { value: 5, label: "Fri" },
  { value: 6, label: "Sat" },
  { value: 7, label: "Sun" },
]

/** "Every weekday at 08:00"-style description of when a schedule runs. */
export function describeFrequency(s: { frequency: string; daysOfWeek?: number[]; dayOfMonth?: number | null; timeOfDay?: string }): string {
  const at = s.timeOfDay ? ` at ${s.timeOfDay.slice(0, 5)}` : ""
  if (s.frequency === "hourly") return `Every hour at minute ${s.timeOfDay ? s.timeOfDay.slice(3, 5) : "00"}`
  if (s.frequency === "daily") return `Every day${at}`
  if (s.frequency === "weekly") {
    const days = (s.daysOfWeek ?? []).slice().sort().map((d) => WEEKDAYS.find((w) => w.value === d)?.label ?? d)
    return `Every ${days.join(", ") || "week"}${at}`
  }
  return `Monthly on ${s.dayOfMonth === -1 ? "the last day" : `day ${s.dayOfMonth ?? "?"}`}${at}`
}

export const FORMAT_LABELS: Record<string, string> = { csv: "CSV", xlsx: "Excel", pdf: "PDF" }
