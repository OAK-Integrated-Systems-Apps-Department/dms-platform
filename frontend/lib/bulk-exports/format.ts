const UNITS: [Intl.RelativeTimeFormatUnit, number][] = [
  ["day", 86_400_000],
  ["hour", 3_600_000],
  ["minute", 60_000],
]

const relative = new Intl.RelativeTimeFormat(undefined, { numeric: "auto" })

/** "in 5 hours", "3 minutes ago", "now" - the largest whole unit, for an ISO timestamp relative to `now`. */
export function formatRelativeTime(iso: string, now: number = Date.now()): string {
  const diff = new Date(iso).getTime() - now
  for (const [unit, ms] of UNITS) {
    if (Math.abs(diff) >= ms) return relative.format(Math.round(diff / ms), unit)
  }
  return relative.format(0, "minute")
}
