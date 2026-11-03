import { Archive, CircleCheck, CirclePause } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import type { LifecycleState } from "@/lib/retention/api"
import { formatDateTime } from "@/lib/utils"

const LABELS: Record<LifecycleState, string> = { active: "Active", inactive: "Inactive", archived: "Archived" }

/**
 * The status indicator on every document (CRM-FR-001, CRM-NFR-007): Active, Inactive (switched off by hand) or
 * Archived (by its retention rule). Distinct in colour AND in words and icon, so it never relies on colour alone.
 * An active document that a rule will archive says when, on hover.
 */
export function LifecycleBadge({
  state,
  archiveDueAt,
  className,
}: {
  state: LifecycleState | undefined
  archiveDueAt?: string | null
  className?: string
}) {
  const current: LifecycleState = state ?? "active"
  const title =
    current === "active" && archiveDueAt
      ? `Active - will be archived on ${formatDateTime(archiveDueAt)}`
      : current === "inactive"
        ? "Inactive - hidden from normal listings; find it in Inactive & Archive"
        : current === "archived"
          ? "Archived by its retention rule - find it in Inactive & Archive"
          : "Active"
  const Icon = current === "active" ? CircleCheck : current === "inactive" ? CirclePause : Archive
  return (
    <Badge variant={current === "active" ? "default" : current === "inactive" ? "muted" : "gold"} className={className} title={title}>
      <Icon aria-hidden="true" />
      {LABELS[current]}
    </Badge>
  )
}
