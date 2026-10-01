import type { ExportJobStatus, ExportType } from "./api"

/** Display label + badge variant per export job status - shared by any view of an export job. */
export const EXPORT_JOB_STATUS_LABELS: Record<ExportJobStatus, string> = {
  pending: "Queued",
  processing: "Preparing",
  completed: "Ready",
  failed: "Failed",
  expired: "Expired",
}

export const EXPORT_JOB_STATUS_BADGE_VARIANT: Record<ExportJobStatus, "default" | "muted" | "gold" | "rust" | "outline"> = {
  pending: "muted",
  processing: "gold",
  completed: "default",
  failed: "rust",
  expired: "outline",
}

export const EXPORT_TYPE_LABELS: Record<ExportType, string> = {
  single_document: "Single document",
  bulk_folder: "Folder (bulk)",
  report_data: "Report data",
}
