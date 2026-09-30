import type { QuarantineStatus, ReleaseAttemptStatus } from "./api"

/** Display label + badge variant per quarantine record status - shared by the list and the detail page. */
export const QUARANTINE_STATUS_LABELS: Record<QuarantineStatus, string> = {
  held: "Held",
  released: "Released",
  deleted: "Deleted",
}

export const QUARANTINE_STATUS_BADGE_VARIANT: Record<QuarantineStatus, "rust" | "default" | "muted"> = {
  held: "rust",
  released: "default",
  deleted: "muted",
}

/** How a release attempt ended, from the reviewer's point of view. */
export const RELEASE_ATTEMPT_LABELS: Record<ReleaseAttemptStatus, string> = {
  pending: "Re-scan in progress",
  infected: "Re-scan in progress",
  clean: "Released (re-scan clean)",
  quarantined: "Refused (still detected)",
  released_override: "Released (override)",
  failed: "Could not re-scan",
  unscannable: "Could not re-scan",
}

export const RELEASE_ATTEMPT_BADGE_VARIANT: Record<ReleaseAttemptStatus, "rust" | "default" | "muted" | "gold"> = {
  pending: "muted",
  infected: "muted",
  clean: "default",
  quarantined: "rust",
  released_override: "gold",
  failed: "muted",
  unscannable: "muted",
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  const units = ["KB", "MB", "GB"]
  let value = bytes / 1024
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit++
  }
  return `${value.toFixed(value >= 10 ? 0 : 1)} ${units[unit]}`
}
