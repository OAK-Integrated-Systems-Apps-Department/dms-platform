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
