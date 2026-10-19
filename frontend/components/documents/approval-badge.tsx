"use client"

import { Badge } from "@/components/ui/badge"
import type { DocumentWorkflowStatusDto } from "@/lib/workflow/api"
import {
  INSTANCE_STATUS_BADGE_VARIANT,
  INSTANCE_STATUS_LABELS,
} from "@/lib/workflow/workflow-badges"

/** "In approval" reads better on a document row than the run's own "In progress". */
const ROW_LABELS: Record<string, string> = {
  ...INSTANCE_STATUS_LABELS,
  in_progress: "In approval",
}

/**
 * Where a document stands in its newest approval, on a row or tile of My
 * Documents. Nothing is shown for a document that was never submitted, or
 * while the statuses are still loading.
 */
export function ApprovalBadge({
  status,
  className,
}: {
  status?: DocumentWorkflowStatusDto
  className?: string
}) {
  if (!status) return null
  return (
    <Badge
      variant={INSTANCE_STATUS_BADGE_VARIANT[status.status]}
      className={className}
      title={
        status.versionChangedFlag
          ? "The document changed after it was submitted"
          : undefined
      }
    >
      {ROW_LABELS[status.status] ?? status.status}
    </Badge>
  )
}
