import { Badge } from "@/components/ui/badge"
import type { RunStatus } from "@/lib/reports/api"

const LABELS: Record<RunStatus, string> = {
  queued: "Waiting",
  running: "Running",
  completed: "Ready",
  failed: "Failed",
  cancelled: "Cancelled",
  expired: "Expired",
}

const VARIANTS: Record<RunStatus, "default" | "muted" | "outline" | "gold" | "rust"> = {
  queued: "muted",
  running: "gold",
  completed: "default",
  failed: "rust",
  cancelled: "muted",
  expired: "outline",
}

/** A run's state in plain words. */
export function RunStatusBadge({ status }: { status: RunStatus }) {
  return <Badge variant={VARIANTS[status] ?? "muted"}>{LABELS[status] ?? status}</Badge>
}
