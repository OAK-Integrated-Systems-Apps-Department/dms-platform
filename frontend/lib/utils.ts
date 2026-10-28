import { clsx, type ClassValue } from "clsx"
import { twMerge } from "tailwind-merge"

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

/**
 * Formats an ISO timestamp as "YYYY-MM-DD, HH:mm" (24-hour, local time) —
 * the same date+time convention already used app-wide for timestamps that
 * need more than a bare date, e.g. `UserActivityEntry.time` and
 * `UserSessionEntry.startedAt` (see components/users/types.ts). Use this
 * rather than a plain `.slice(0, 10)` date-only cut wherever a field
 * actually needs to show the time, not just the day.
 */
export function formatDateTime(iso: string): string {
  const date = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, "0")
  const day = `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
  const time = `${pad(date.getHours())}:${pad(date.getMinutes())}`
  return `${day}, ${time}`
}

/**
 * A short, human date for file and folder listings (SharePoint style):
 * "Just now", "12 minutes ago", "3 hours ago", "Yesterday at 14:05",
 * "Monday at 09:30", "1 Oct", or "1 Oct 2025" for another year. Pair it with
 * {@link formatDateTime} in a tooltip when the exact time matters.
 */
export function formatFriendlyDate(
  iso: string,
  now: Date = new Date()
): string {
  const date = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, "0")
  const time = `${pad(date.getHours())}:${pad(date.getMinutes())}`
  const minutes = Math.floor((now.getTime() - date.getTime()) / 60_000)
  if (minutes < 1) return "Just now"
  if (minutes < 60)
    return `${minutes} ${minutes === 1 ? "minute" : "minutes"} ago`
  const startOfDay = (d: Date) =>
    new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()
  const days = Math.round((startOfDay(now) - startOfDay(date)) / 86_400_000)
  if (days === 0) {
    const hours = Math.floor(minutes / 60)
    return `${hours} ${hours === 1 ? "hour" : "hours"} ago`
  }
  if (days === 1) return `Yesterday at ${time}`
  if (days > 1 && days < 7) {
    return `${date.toLocaleDateString(undefined, { weekday: "long" })} at ${time}`
  }
  const sameYear = date.getFullYear() === now.getFullYear()
  return date.toLocaleDateString(undefined, {
    day: "numeric",
    month: "short",
    ...(sameYear ? {} : { year: "numeric" }),
  })
}
