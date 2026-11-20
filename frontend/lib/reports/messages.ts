import { ApiError, friendlyMessage } from "@/lib/api-error"

/** What a failed report action says, by the server's stable error code; unknown codes use the server's own message. */
const BY_CODE: Record<string, string> = {
  REPORTS_DISABLED: "Reports are turned off for your organisation.",
  REPORT_RATE_LIMITED: "You already have as many reports running as you may. Wait for one to finish, then try again.",
  REPORT_ROW_LIMIT_EXCEEDED: "This report is too large for that file format. Narrow the filters, or choose another format.",
  REPORT_FORMAT_NOT_ALLOWED: "That file format is not available for your organisation.",
  TEMPLATE_NAME_TAKEN: "You already have a report with that name.",
  TEMPLATE_NAME_INVALID: "Give the report a name of up to 150 characters.",
  TEMPLATE_LIMIT_REACHED: "You have reached the number of saved reports you may keep.",
  TEMPLATE_HAS_SCHEDULES: "This report is scheduled. Cancel its schedules first.",
  TEMPLATE_NOT_FOUND: "That report was not found, or you cannot use it.",
  SCHEDULE_LIMIT_REACHED: "You have reached the number of schedules you may keep.",
  SCHEDULE_OWNER_INACTIVE: "The owner of this schedule is no longer an active user. Hand it to someone else first.",
  SCHEDULE_NOT_ACTIVE: "This schedule has been cancelled or has ended.",
  REASSIGN_OWNER_INACTIVE: "That person is not an active user of your organisation.",
  REASSIGN_OWNER_CANNOT_RUN: "That person is not allowed to run these reports.",
  REASSIGN_OWNER_NO_TEMPLATE_ACCESS: "That person cannot see this report. Share it with them first.",
  REPORT_FIELD_NOT_FOUND: "That field is not available.",
  REPORT_FIELD_HAS_NO_CHOICES: "That field takes a typed value.",
}

export function reportError(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    if (error.code && BY_CODE[error.code]) return BY_CODE[error.code]
    if (error.status === 403) return "You do not have permission to do that."
  }
  return friendlyMessage(error, fallback)
}

/** Per-field problems the server returned with a 422 (details), keyed by field, for inline display. */
export function fieldProblems(error: unknown): Record<string, string> {
  const details = error instanceof ApiError ? (error as { details?: unknown }).details : undefined
  return details && typeof details === "object" ? (details as Record<string, string>) : {}
}
