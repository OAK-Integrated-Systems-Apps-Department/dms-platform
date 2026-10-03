import type { ReactNode } from "react"
import { AlertTriangle, Ban, CheckCircle2, Clock, Loader2, Upload, XCircle } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import type { BulkImportStatus } from "@/lib/bulk-imports/api"

type BadgeVariant = "default" | "muted" | "outline" | "gold" | "rust"

const STATUS_BADGES: Record<BulkImportStatus, { label: string; variant: BadgeVariant; icon: ReactNode }> = {
  uploading: { label: "Uploading", variant: "muted", icon: <Upload aria-hidden="true" /> },
  queued: { label: "Queued", variant: "gold", icon: <Clock aria-hidden="true" /> },
  running: { label: "Processing", variant: "outline", icon: <Loader2 className="animate-spin" aria-hidden="true" /> },
  completed: { label: "Completed", variant: "default", icon: <CheckCircle2 aria-hidden="true" /> },
  completed_with_errors: {
    label: "Completed with errors",
    variant: "gold",
    icon: <AlertTriangle aria-hidden="true" />,
  },
  failed: { label: "Failed", variant: "rust", icon: <XCircle aria-hidden="true" /> },
  cancelled: { label: "Cancelled", variant: "outline", icon: <Ban aria-hidden="true" /> },
}

/** A batch's status as a badge - same labels, icons and colours as the status and results cards. */
export function ImportStatusBadge({ status }: { status: BulkImportStatus }) {
  const { label, variant, icon } = STATUS_BADGES[status]
  return (
    <Badge variant={variant}>
      {icon}
      {label}
    </Badge>
  )
}
