import type { ReactNode } from "react"
import { CheckCircle2, Clock, Hourglass, Loader2, XCircle } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import type { ExportJobStatus } from "@/lib/bulk-exports/api"

type BadgeVariant = "default" | "muted" | "outline" | "gold" | "rust"

const STATUS_BADGES: Record<ExportJobStatus, { label: string; variant: BadgeVariant; icon: ReactNode }> = {
  pending: { label: "Queued", variant: "gold", icon: <Clock aria-hidden="true" /> },
  processing: { label: "Processing", variant: "outline", icon: <Loader2 className="animate-spin" aria-hidden="true" /> },
  completed: { label: "Ready", variant: "default", icon: <CheckCircle2 aria-hidden="true" /> },
  failed: { label: "Failed", variant: "rust", icon: <XCircle aria-hidden="true" /> },
  expired: { label: "Expired", variant: "muted", icon: <Hourglass aria-hidden="true" /> },
}

/** An export's status as a badge - same look as the bulk import's `ImportStatusBadge`. */
export function ExportStatusBadge({ status }: { status: ExportJobStatus }) {
  const { label, variant, icon } = STATUS_BADGES[status]
  return (
    <Badge variant={variant}>
      {icon}
      {label}
    </Badge>
  )
}
