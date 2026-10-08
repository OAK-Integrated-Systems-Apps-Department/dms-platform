import type { WatermarkJobStatus } from "./api"

/**
 * Display label + badge variant per watermark job status - shared by the
 * list, the post-submit tracker and anywhere else a job is shown, so one
 * status never reads two different ways.
 *
 * Deliberately the same wording as exports ("Queued"/"Preparing"/"Ready")
 * rather than the raw backend values: a user tracking a watermarked copy
 * and a user tracking an export are doing the same thing and should not
 * have to learn two vocabularies.
 */
export const WATERMARK_JOB_STATUS_LABELS: Record<WatermarkJobStatus, string> = {
  pending: "Queued",
  processing: "Preparing",
  completed: "Ready",
  failed: "Failed",
  expired: "Expired",
}

export const WATERMARK_JOB_STATUS_BADGE_VARIANT: Record<
  WatermarkJobStatus,
  "default" | "muted" | "gold" | "rust" | "outline"
> = {
  pending: "muted",
  processing: "gold",
  completed: "default",
  failed: "rust",
  expired: "outline",
}
